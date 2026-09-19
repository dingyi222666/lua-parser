package io.github.dingyi222666.luaparser.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Material 3 bottom sheet (View-based [BottomSheetDialogFragment], NOT
 * Compose) listing the workspace sources under `filesDir/project`
 * ([ProjectBootstrapper.PROJECT_ASSET_DIR]) — `.lua` and `.aly` files only,
 * shown as project-relative paths.
 *
 * Tapping an entry hands the relative path to the host [Listener]
 * ([MainActivity]), which loads it into the CodeEditor and (re)attaches the
 * LSP bridge via `textDocument/didOpen`.
 *
 * No Fragment factory arguments needed: the workspace root is derived from
 * the host context, mirroring how [ProjectBootstrapper] lays it out.
 */
class FileBrowserFragment : BottomSheetDialogFragment() {

    /** Host callback — implemented by [MainActivity]. */
    interface Listener {
        fun onFileSelected(relativePath: String)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_file_browser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.file_list)
        val emptyView = view.findViewById<TextView>(R.id.browser_empty)

        val adapter = FileListAdapter { relativePath ->
            (activity as? Listener)?.onFileSelected(relativePath)
            dismissAllowingStateLoss()
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { listWorkspaceFiles() }
            adapter.submit(files)
            emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * Recursive `.lua`/`.aly` listing of the materialized workspace, as
     * project-relative paths sorted for stable browsing (`layout/main.aly`,
     * `main.lua`, `mods/dingyi.lua`, ...).
     */
    private fun listWorkspaceFiles(): List<String> {
        val root = File(requireContext().filesDir, ProjectBootstrapper.PROJECT_ASSET_DIR)
        val extensions = setOf("lua", "aly")
        return root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .mapNotNull { file -> file.relativeToOrNull(root)?.invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    /** Minimal RecyclerView adapter; entries are plain relative path strings. */
    private class FileListAdapter(
        private var entries: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

        constructor(onClick: (String) -> Unit) : this(emptyList(), onClick)

        fun submit(files: List<String>) {
            entries = files
            notifyDataSetChanged()
        }

        class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.file_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return FileViewHolder(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            val relativePath = entries[position]
            holder.name.text = relativePath
            holder.itemView.setOnClickListener { onClick(relativePath) }
        }
    }

    companion object {
        const val TAG = "FileBrowserFragment"

        fun newInstance(): FileBrowserFragment = FileBrowserFragment()
    }
}
