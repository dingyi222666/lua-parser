# Android framework overlay resources

TASK-056 adds compact Android framework package resources for Android-Lua
workspace modeling. These files are original resource metadata, not copied
Android SDK source.

Class indexes were seeded by read-only enumeration of class entries from:

- `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`

Manual curation then narrowed the package surface to APIs that are common in
Android-Lua code and the repository fixtures: activity/service globals,
`import "android.widget.*"`, `import "android.view.*"`, layout table widgets,
drawable styling helpers, context service constants, intents, and common
nested listener/enum classes.

The existing Android-Lua resource catalog at
`src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/androlua5.3/classes/android-framework.index`
was used as an additional compatibility seed. Its provenance notes identify
Android-Lua checkout `/Users/dingyi/projects/java_projects/Android-Lua` at commit
`686a792dbdd2fe9727a34768ceadffcaa2abc20d`.

Conventions:

- `packages/*.index` files use JVM binary names for nested classes, for example
  `android.view.View$OnClickListener`.
- `models/*.lua` files use Lua/Emmy-style dotted nested names where practical,
  for example `android.view.View.OnClickListener`.
- Model members are intentionally high-value and incomplete. They cover stable
  constants and methods used by Android-Lua imports, layout helpers, and common
  UI styling code. Full android.jar reflection remains the JVM-specific source
  of exhaustive member data.
- Loader integration and serialized Gradle verification are deferred to later
  tasks; TASK-056 is resource-only.
