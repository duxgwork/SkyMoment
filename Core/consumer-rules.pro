# 默认 dex 扫描通过类名发现生成表，R8 必须保留。
-keep public class com.alibaba.android.arouter.routes.** { *; }
-keep public class com.alibaba.android.arouter.facade.** { *; }
-keep class * implements com.alibaba.android.arouter.facade.template.ISyringe { *; }
# 内建拦截/注入服务通过 getConstructor().newInstance() 创建。
-keep class * implements com.alibaba.android.arouter.facade.template.IProvider { public <init>(); }
# RouteMeta.rawType 仅供注解处理阶段使用，Android 运行时不访问 JDK 编译器类型。
-dontwarn javax.lang.model.element.Element
