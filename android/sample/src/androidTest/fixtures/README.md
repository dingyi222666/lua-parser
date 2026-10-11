# Runtime DEX Completion Fixture

`../assets/runtime-greeter.dex` contains only `fixture.dynamic.Greeter` from
`Greeter.java`. The Java source is outside the Android test source set, so the
class is absent from both APK classpaths. The E2E test first verifies its members
are unavailable, copies the DEX into the running project's `libs` directory,
imports it in Lua, and verifies static, returned-instance, and partial colon
completion through the real editor and LSP connection.

The checked-in DEX is compiled on the SSH host with JDK 17 and Android build tools
35.0.0. Regenerate remotely from the repository root:

```sh
export JAVA_HOME=/home/dingyi/.jdks/temurin-17
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p /tmp/lua-runtime-greeter/classes /tmp/lua-runtime-greeter/dex
javac --release 8 -d /tmp/lua-runtime-greeter/classes \
  android/sample/src/androidTest/fixtures/Greeter.java
/home/dingyi/Android/Sdk/build-tools/35.0.0/d8 --min-api 26 \
  --output /tmp/lua-runtime-greeter/dex \
  /tmp/lua-runtime-greeter/classes/fixture/dynamic/Greeter.class
cp /tmp/lua-runtime-greeter/dex/classes.dex \
  android/sample/src/androidTest/assets/runtime-greeter.dex
```
