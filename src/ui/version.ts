/**
 * 应用版本号（唯一来源）。
 *
 * 发布新版本时改这里，并同步修改 `android/app/build.gradle` 的
 * versionName / versionCode（Android 侧无法直接读前端常量）。
 *
 * 对应关系：
 *   versionName = APP_VERSION
 *   versionCode = 主版本 * 10 + 次版本（2.0 → 20），必须单调递增
 */
export const APP_VERSION = '2.0'
