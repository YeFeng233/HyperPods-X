-dontwarn io.github.libxposed.annotation.**
# Keep the API 102 entry name stable when R8 rewrites the module APK.
-adaptresourcefilecontents META-INF/xposed/java_init.list
# The framework discovers this class through java_init.list, outside the app's call graph.
-keep class com.hyper.podsrx.hook.** { *; }
# The hooks call these from inside the hosts, where the app's call graph cannot vouch for them.
-keep class com.hyper.podsrx.protocol.** { *; }
