import extensions.ApplicationExtension

plugins {
    id("app.base.android")
    id("app.base.multiplatform")
}

/**
 * 该插件封装了大部分模版配置，其余配置由 [ApplicationExtension] 配置
 */

extensions.create("composeApplication", ApplicationExtension::class.java, project)