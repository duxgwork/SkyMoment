# 仅 routeSmoke=true 的本机 release 测试使用。
# 数据注入在应用内执行，测试 APK 不打包另一套精简 java.time。
-keep class com.example.skymoment.RouteSmokeData { public static *; }
