package com.example.skymoment.core.router

import com.alibaba.android.arouter.launcher.ARouter

/** 只负责按地址查找服务；业务契约由调用方所属层定义，导航层不引用业务类型。 */
object ServiceLocator {
    // 通过服务路由取得实例并校验契约类型，未注册或类型不匹配时立即失败。
    fun <T : Any> get(path: String, contract: Class<T>): T {
        val service = ARouter.getInstance().build(path).navigation()
        check(contract.isInstance(service)) { "Service $path does not implement ${contract.name}" }
        return contract.cast(service)!!
    }
}
