# release 包去掉 Log.d / Log.v（按键日志、滚动诊断等），保留 i/w/e（启动耗时、错误）。
# 按键日志会把实体键盘打的英文字母写进 logcat，不应留在发布包里（docs/RELEASE.md §5）。
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
