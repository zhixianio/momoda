# 墨墨打官网

https://momoda.zhixian.io 的源文件。纯静态 HTML/CSS，没有构建步骤，GitHub Pages 直接发布这个分支。

- `index.html`、`style.css`：页面。和 App 一样白底黑字、不做动画、版心每行约 30 字。
- `img/`：模拟器截图（灰度 WebP）。
- `fonts/wenkai-screen-subset.woff2`：霞鹜文楷屏幕阅读版的子集，只含页面用到的字。**改了页面文字后要重新生成**：
  ```bash
  pip install fonttools brotli
  python3 tools/build-font.py /path/to/LXGWWenKaiScreen.ttf
  ```
  字体授权：SIL Open Font License 1.1，见 `fonts/OFL.txt`。
- `CNAME`：自定义域名 momoda.zhixian.io。
