// 墨墨打官网：打字演示 + 会眨眼的图标 + 下载区读取最新发布。
// 演示按 App 的真实行为来画：中文按输入法上屏的节奏一个词一个词出现；光标常亮不闪；
// 专注模式（当前段落黑色，其余浅灰）；Markdown 标记淡显；字数在停笔后才更新；
// Vim 的 dd 删除整段、u 撤销；命令面板支持拼音首字母（tb → 立即同步）；Ctrl+. 隐藏页眉页脚。

(() => {
  // ?static：直接显示最后的样子（截图、检查排版用），和“减少动态效果”一样
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches || /[?&]static\b/.test(location.search);

  // ---------- 眨眼：墨点是眼睛，光标从来不闪 ----------
  for (const svg of document.querySelectorAll(".blinker")) {
    if (reduced) break;
    const eyes = svg.querySelector(".eyes");
    const once = () => {
      eyes.classList.add("shut");
      setTimeout(() => eyes.classList.remove("shut"), 140);
    };
    const loop = () => {
      once();
      if (Math.random() < 0.25) setTimeout(once, 320);   // 偶尔连眨两下
      setTimeout(loop, 2600 + Math.random() * 4200);
    };
    setTimeout(loop, 1200 + Math.random() * 2000);
  }

  // ---------- 下载：读最新的发布（包括预览版），没有就保持“即将发布” ----------
  const note = document.getElementById("release-note");
  const link = document.getElementById("release-link");
  if (note && link) {
    fetch("https://api.github.com/repos/zhixianio/momoda/releases?per_page=1")
      .then((r) => (r.ok ? r.json() : []))
      .then(([rel]) => {
        if (!rel) return;
        const ver = rel.tag_name.replace(/^v/, "");
        const apk = (rel.assets || []).find((x) => /\.apk$/.test(x.name));
        note.textContent = rel.prerelease ? `预览版 ${ver}，仍在持续调整。` : `当前版本 ${ver}。`;
        link.textContent = "下载 APK";
        link.href = apk ? apk.browser_download_url : rel.html_url;
      })
      .catch(() => {});
  }

  // ---------- 打字演示 ----------
  const demo = document.getElementById("demo");
  if (!demo) return;
  const textEl = demo.querySelector(".text");
  const pageEl = demo.querySelector(".page");
  const titleEl = demo.querySelector(".doc-title");
  const countEl = demo.querySelector(".count");
  const syncEl = demo.querySelector(".sync");
  const keysEl = document.querySelector(".keys");
  const actBtns = [...document.querySelectorAll(".acts button")];

  // 命令面板里的命令（和 App 一致），仓库名是虚构的
  const COMMANDS = [
    ["用 GitHub 登录（同步）", "手机或电脑上输入验证码"],
    ["导出诊断信息", "崩溃记录等，不含文档内容"],
    ["管理文档", "回收站、排序"],
    ["新建文档", "Ctrl+N"],
    ["打开文档", "Ctrl+O"],
    ["切回上一篇", "Ctrl+Tab"],
    ["设置", "Ctrl+,"],
    ["立即同步", "fors/novel · Ctrl+Shift+S"],
    ["GitHub 同步设置", "fors/novel"],
  ];
  const TB = ["立即同步", "GitHub 同步设置", "用 GitHub 登录（同步）"];   // 输入 tb 后的结果（都含“同步”）

  const SPEED = 0.7;   // 整体节奏（越小越快）
  // 时间线：每一步 [幕, 动作, 参数, 之后停多久]。
  // 佛尔思（“魔术师”小姐）的口吻：“世界”先生委托她把格尔曼·斯帕罗的冒险写成小说，每次塔罗会都问进度。
  // 文字里的 “/” 是输入法一次上屏的分界。
  const T = [];
  const act = (a, ...steps) => steps.forEach((s) => T.push([a, ...s]));
  const typePara = (a, para, first) => {
    if (!first) T.push([a, "type", "\n\n", 120]);
    for (const piece of para.split("/")) {
      if (/^[\x20-\x7e]+$/.test(piece)) {
        // 英文、数字和 Markdown 标记不经过输入法，一个键一个键地出现
        [...piece].forEach((ch, i) => T.push([a, "type", ch, i === piece.length - 1 ? 90 : 45]));
      } else if (/^[，。、：？“”]$/.test(piece)) {
        T.push([a, "type", piece, piece === "。" ? 160 : 70]);
      } else {
        T.push([a, "type", piece, 70 + piece.length * 35 + Math.random() * 70]);
      }
    }
    T.push([a, "pause", null, 450]);
  };

  // 第一幕：写作
  typePara(0, "被/催稿/的/一天", true);
  typePara(0, "“/魔术师/”/小姐/，/那本/小说/进度/怎么样/了/？");
  typePara(0, "“/世界/”/先生/只/问了/一句/，/我/就/坐直/了/。");
  typePara(0, "我/说/前五章/已经/写完/了/。");
  typePara(0, "其实/只/写了/开头/。");
  // 第二幕：Vim（dd 删掉整段，想想还是 u 撤销回来，再 A 接着写）
  act(1,
    ["key", "Esc", 0], ["mode", "normal", 700],
    ["key", "d", 220], ["key", "d d", 0], ["dd", null, 1100],
    ["key", "u", 0], ["undo", null, 900],
    ["key", "A", 0], ["mode", "insert", 350],
    ["key", "Enter", 0], ["type", "\n\n", 200], ["keyoff", null, 0]);
  typePara(1, "回到/现实/，/我/从/床上/爬起来/，/抓起/了/钢笔/。", true);
  typePara(1, "**/赞美/愚者/。/**/今晚/一定/写完/第三章/。");
  // 第三幕：命令面板，拼音首字母 tb → 立即同步（交稿）
  act(2,
    ["key", "Ctrl K", 0], ["palette", "", 800],
    ["key", "t", 0], ["palette", "t", 260],
    ["key", "b", 0], ["palette", "tb", 900],
    ["key", "Enter", 0], ["palette", null, 0], ["sync", "同步中", 700], ["sync", "已同步 22:43", 1200]);
  // 第四幕：Zen（Ctrl+. 隐藏页眉页脚），写下内心独白
  act(3, ["key", "Ctrl .", 0], ["zen", true, 700], ["keyoff", null, 0]);
  typePara(3, "我/可不想/以/非凡/特性/的/形态/出席/下周/的/塔罗会/。");
  T.push([3, "pause", null, 3200]);
  T.forEach((s) => { s[3] *= SPEED; });
  const actStart = [0, 1, 2, 3].map((a) => T.findIndex((s) => s[0] === a));

  // ---------- 状态与渲染 ----------
  let st;
  const fresh = () => ({ buf: "# ", mode: "insert", stash: null, palette: null, sync: "", zen: false });

  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const quotes = (s) => s.replace(/[“”]/g, (q) => `<span class="q">${q}</span>`);   // 紧挨汉字的引号用全角字形
  function inline(s) {
    let out = "", i = 0, m;
    const re = /\*\*(.+?)\*\*/g;
    while ((m = re.exec(s))) {
      out += quotes(esc(s.slice(i, m.index)));
      out += `<span class="mk">**</span><b>${quotes(esc(m[1]))}</b><span class="mk">**</span>`;
      i = re.lastIndex;
    }
    return out + quotes(esc(s.slice(i)).replace(/\*\*/g, '<span class="mk">**</span>'));
  }

  function render() {
    const paras = st.buf.split("\n\n");
    textEl.innerHTML = paras.map((p, idx) => {
      const last = idx === paras.length - 1;
      const title = p.startsWith("# ");
      const t = title ? p.slice(2) : p;
      let body;
      if (last && st.mode === "normal") {
        // normal 模式：光标是盖住最后一个字的黑色方块
        body = inline(t.slice(0, -1)) + `<span class="block">${esc(t.slice(-1) || " ")}</span>`;
      } else {
        body = inline(t) + (last ? '<span class="caret"></span>' : "");
      }
      if (title) body = `<span class="mk"># </span><span class="h1">${body}${t ? "" : '<span class="ph">标题</span>'}</span>`;
      return `<p class="${last ? "cur" : "dim"}${title ? " title" : ""}">${body}</p>`;
    }).join("");
    titleEl.textContent = paras[0].replace(/^#\s*/, "").replace(/\*/g, "") || "标题";
    demo.classList.toggle("zen", st.zen);
    syncEl.textContent = st.sync ? ` · ${st.sync}` : "";
    renderPalette();
    flipIfNeeded();
  }

  let paletteEl = null;
  function renderPalette() {
    if (st.palette === null) { paletteEl?.remove(); paletteEl = null; return; }
    if (!paletteEl) { paletteEl = document.createElement("div"); paletteEl.className = "palette"; demo.appendChild(paletteEl); }
    const q = st.palette;
    const items = q === "tb" ? TB.map((n) => COMMANDS.find((c) => c[0] === n))
      : q === "t" ? COMMANDS.filter((c) => /同步|调|Tab/.test(c[0] + c[1]))
      : COMMANDS.slice(0, 7);
    paletteEl.innerHTML = `<div class="ttl">命令</div>
      <div class="q">${q ? esc(q) : '<span class="ph">输入筛选，支持拼音首字母</span>'}</div>
      ${items.map((c, i) => `<div class="item${i === 0 ? " sel" : ""}"><span>${esc(c[0])}</span><span class="hint">${esc(c[1])}</span></div>`).join("")}`;
  }

  // 写到页底：整体上移，把当前行放到屏幕中部（专注模式下的翻半页），没有滚动动画
  let offset = 0;
  function flipIfNeeded() {
    const caret = textEl.querySelector(".caret, .block");
    if (!caret) return;
    const pageH = pageEl.clientHeight;
    const line = parseFloat(getComputedStyle(textEl).lineHeight);
    if (caret.offsetTop + caret.offsetHeight - offset > pageH - line * 0.6) {
      offset = Math.max(0, caret.offsetTop - pageH / 2);
      textEl.style.transform = `translateY(${-offset}px)`;
    }
  }

  const count = (buf) => (buf.match(/[一-鿿]/g) || []).length +
    (buf.replace(/[#*]/g, " ").match(/[A-Za-z0-9]+/g) || []).length;
  let countTimer = null;
  function touchCount(now) {
    clearTimeout(countTimer);
    // 字数和保存状态在停笔后才更新，不跟着每个字刷新
    const upd = () => { countEl.textContent = `${count(st.buf)} 字 · 已保存`; };
    if (now) upd(); else countTimer = setTimeout(upd, 500);
  }

  function showKeys(label) {
    if (keysEl) keysEl.innerHTML = label ? label.split(" ").map((k) => `<kbd>${esc(k)}</kbd>`).join("") : "";
  }

  function apply([, op, arg]) {
    switch (op) {
      case "type": st.buf += arg; touchCount(); break;
      case "mode": st.mode = arg; break;
      case "dd": {
        const i = st.buf.lastIndexOf("\n\n");
        st.stash = st.buf.slice(i); st.buf = st.buf.slice(0, i); touchCount(); break;
      }
      case "undo": st.buf += st.stash || ""; st.stash = null; touchCount(); break;
      case "palette": st.palette = arg; break;
      case "sync": st.sync = arg; break;
      case "zen": st.zen = arg; break;
      case "key": showKeys(arg); break;
      case "keyoff": showKeys(""); break;
    }
  }

  const markAct = (a) => actBtns.forEach((b, i) => b.classList.toggle("on", i === a));

  // ---------- 播放 ----------
  let pos = 0, timer = null, running = false;

  // 从头瞬间重放到第 i 步，之后从那里按节奏播放
  function seek(i) {
    clearTimeout(timer);
    st = fresh(); offset = 0; textEl.style.transform = "";
    for (let k = 0; k < i; k++) if (T[k][1] !== "key" && T[k][1] !== "keyoff") apply(T[k]);
    showKeys("");
    touchCount(true);
    pos = i;
    markAct(T[Math.min(i, T.length - 1)][0]);
    render();
  }

  function tick() {
    if (!running) return;
    if (pos >= T.length) { seek(0); timer = setTimeout(tick, 900); return; }
    const step = T[pos++];
    apply(step);
    markAct(step[0]);
    render();
    timer = setTimeout(tick, step[3]);
  }

  function start() { if (!running) { running = true; timer = setTimeout(tick, 400); } }
  function stop() { running = false; clearTimeout(timer); }

  actBtns.forEach((b, a) => b.addEventListener("click", () => {
    seek(actStart[a]);
    if (running) timer = setTimeout(tick, 300);
  }));

  if (reduced) {
    // 不播放：直接显示写完、已同步的样子
    seek(T.length);
    st.zen = false;
    render();
    if (keysEl) keysEl.hidden = true;
    document.querySelector(".acts")?.setAttribute("hidden", "");
    return;
  }

  seek(0);
  // 只在看得见的时候播放
  let inView = false;
  const update = () => (inView && !document.hidden ? start() : stop());
  document.fonts.ready.then(() => {
    new IntersectionObserver(([e]) => { inView = e.isIntersecting; update(); }).observe(demo);
    document.addEventListener("visibilitychange", update);
  });
})();
