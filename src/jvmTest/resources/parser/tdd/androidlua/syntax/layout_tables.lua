return {
    LinearLayout,
    orientation = "vertical",
    id = "root",
    {
        TextView,
        text = "Hello",
        onClick = lambda view -> view:getId()
    },
    Button {
        text = "Save",
        onClick = lambda view -> save(view)
    }
}
