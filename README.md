# 墨墨打官网

https://momoda.zhixian.io 的源文件。纯静态 HTML/CSS，没有构建步骤，GitHub Pages 直接发布这个分支。

- `index.html`、`style.css`：页面。和 App 一样白底黑字、不做动画、版心每行约 30 字。
- `site.js`：首页的打字演示（按 App 的真实行为画：输入法节奏上屏、光标不闪、专注模式、Markdown 淡显、停笔后才更新字数）和会眨眼的图标（墨点眨眼，光标不闪）。加 `?static` 或系统开启“减少动态效果”时直接显示写完的样子。
- `fonts/wenkai-screen-subset.woff2`：霞鹜文楷屏幕阅读版的子集，只含页面和 `site.js` 用到的字。**改了页面、样式或脚本后都要运行一次**（同时更新资源地址里的版本号，避免浏览器用旧缓存）：
  ```bash
  pip install fonttools brotli
  python3 tools/build-font.py /path/to/LXGWWenKaiScreen.ttf
  ```
  字体授权：SIL Open Font License 1.1，见 `fonts/OFL.txt`。
- `CNAME`：自定义域名 momoda.zhixian.io。
