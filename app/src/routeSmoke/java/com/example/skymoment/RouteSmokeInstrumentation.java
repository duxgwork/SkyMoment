package com.example.skymoment;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import com.example.skymoment.core.router.NavigationExtras;

/**
 * 混淆包只验证平台启动、真实 ARouter 跳转及 XML 加载。
 * 不依赖会被独立应用压缩影响的 AndroidX 测试运行时；完整交互测试使用 debug Espresso。
 */
public final class RouteSmokeInstrumentation extends Instrumentation {
    // 启动仅依赖 Android 平台的测试入口，避免与压缩后的 AndroidX 测试依赖冲突。
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    // 依次验证启动首页、独立搜索路由、回到首页以及泛型绑定加载。
    @Override public void onStart() {
        Bundle status = new Bundle();
        status.putString("id", "InstrumentationTestRunner");
        status.putString("class", getClass().getName());
        status.putString("test", "homeAndSearchRoutesLoadXmlInMinifiedRelease");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        sendStatus(1, status);
        ActivityMonitor homeMonitor = addMonitor(
                "com.example.skymoment.home.HomeActivity", null, false);
        ActivityMonitor searchMonitor = addMonitor(
                "com.example.skymoment.search.SearchActivity", null, false);
        try {
            if (!RouteSmokeData.checkPinyin()) {
                throw new AssertionError("压缩包 ICU 拼音数据不可用");
            }
            // 使用测试专用合成会话，不访问真实设备位置或公共天气接口。
            String[] token = new String[1];
            runOnMainSync(() -> token[0] = RouteSmokeData.select());
            Intent launch = getTargetContext().getPackageManager()
                    .getLaunchIntentForPackage(getTargetContext().getPackageName());
            if (launch == null) throw new AssertionError("缺少启动入口");
            getTargetContext().startActivity(launch
                    .putExtra(NavigationExtras.SESSION_TOKEN, token[0])
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity firstHome = waitForMonitorWithTimeout(homeMonitor, 5000);
            if (firstHome == null) throw new AssertionError("首页未打开");
            waitForIdleSync();
            runOnMainSync(() -> {
                assertViewText(firstHome, "place_name", "示例区");
                RouteSmokeData.openSearch(firstHome);
            });
            // 通过真实路由创建搜索页，再携带相同会话返回首页。
            Activity search = waitForMonitorWithTimeout(searchMonitor, 5000);
            if (search == null) throw new AssertionError("搜索路由未打开");
            waitForIdleSync();
            addMonitor(homeMonitor);
            runOnMainSync(() -> {
                assertViewText(search, "search_query", null);
                RouteSmokeData.openHome(search, token[0]);
            });
            Activity home = waitForMonitorWithTimeout(homeMonitor, 5000);
            if (home == null) throw new AssertionError("首页路由未打开");
            waitForIdleSync();
            runOnMainSync(() -> {
                assertViewText(home, "place_name", "示例区");
                int statusId = home.getResources().getIdentifier(
                        "current_status", "id", home.getPackageName());
                if (home.findViewById(statusId) == null) throw new AssertionError("天气 Fragment 未加载");
                home.finish();
            });
            // 按 Instrumentation 协议报告成功或异常，供 Gradle 汇总结果。
            status.putString("stream", ".");
            sendStatus(0, status);
            Bundle result = new Bundle();
            result.putString("stream", "\nOK (1 test)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            status.putString("stack", android.util.Log.getStackTraceString(failure));
            sendStatus(-2, status);
            Bundle result = new Bundle();
            result.putString("stream", "\nFAILURES!!!\n" + failure);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            removeMonitor(searchMonitor);
            removeMonitor(homeMonitor);
        }
    }

    // 按资源名检查压缩包内真实 XML 控件，允许只验证存在性而不比较文案。
    private static void assertViewText(Activity activity, String idName, String expected) {
        int id = activity.getResources().getIdentifier(idName, "id", activity.getPackageName());
        TextView view = activity.findViewById(id);
        if (view == null || (expected != null && !expected.contentEquals(view.getText()))) {
            throw new AssertionError("XML 视图不符合预期：" + idName);
        }
    }
}
