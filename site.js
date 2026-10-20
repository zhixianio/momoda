// 墨墨打官网：打字演示 + 会眨眼的图标。
// 演示按 App 的真实行为来画：中文按输入法上屏的节奏一个词一个词出现；光标常亮不闪；
// 专注模式（当前段落黑色，其余浅灰）；Markdown 标记淡显；字数在停笔后才更新；写满就整体翻半页，不滚动。

(() => {
  // ?static：直接显示写完的样子（截图、检查排版用），和“减少动态效果”一样
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

  // ---------- 打字演示 ----------
  const demo = document.getElementById("demo");
  if (!demo) return;
  const textEl = demo.querySelector(".text");
  const pageEl = demo.querySelector(".page");
  const titleEl = demo.querySelector(".doc-title");
  const countEl = demo.querySelector(".count");

  // “/” 是输入法一次上屏的分界，“|” 是回车分段
  // 佛尔斯（魔术师小姐）的口吻：职业作家，能睡就不写
  const SCRIPT = [
    "# /第三章",
    "编辑/又/来信/催稿/了/。",
    "先/睡个/午觉/，/灵感/也/需要/休息/。",
    "醒来/，/休/坐在/对面/，/脸色/比/编辑/还/难看/。",
    "“/今天/写不完/，/晚饭/就/别吃/了/。/”",
    "**/赞美/愚者/。/**/保佑/我/今天/能/写完/。",
  ];

  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  // 紧挨汉字的引号用全角字形
  const quotes = (s) => s.replace(/[“”]/g, (q) => `<span class="q">${q}</span>`);

  function inline(s) {
    let out = "", i = 0;
    const re = /\*\*(.+?)\*\*/g;
    let m;
    while ((m = re.exec(s))) {
      out += quotes(esc(s.slice(i, m.index)));
      out += `<span class="mk">**</span><b>${quotes(esc(m[1]))}</b><span class="mk">**</span>`;
      i = re.lastIndex;
    }
    const rest = esc(s.slice(i)).replace(/\*\*/g, '<span class="mk">**</span>');
    return out + quotes(rest);
  }

  function render(buf) {
    const paras = buf.split("\n\n");
    const html = paras.map((p, idx) => {
      const last = idx === paras.length - 1;
      const caret = last ? '<span class="caret"></span>' : "";
      let body;
      if (p.startsWith("# ")) {
        const t = p.slice(2);
        body = `<span class="mk"># </span><span class="h1">${inline(t)}${caret}${t ? "" : '<span class="ph">标题</span>'}</span>`;
      } else {
        body = inline(p) + caret;
      }
      return `<p class="${last ? "cur" : "dim"}${p.startsWith("# ") ? " title" : ""}">${body}</p>`;
    }).join("");
    textEl.innerHTML = html;
    const title = paras[0].replace(/^#\s*/, "").replace(/\*/g, "");
    titleEl.textContent = title || "标题";
    flipIfNeeded();
  }

  // 写到页底：整体上移，把当前行放到屏幕中部（专注模式下的翻半页），没有滚动动画
  let offset = 0;
  function flipIfNeeded() {
    const caret = textEl.querySelector(".caret");
    if (!caret) return;
    const pageH = pageEl.clientHeight;
    const line = parseFloat(getComputedStyle(textEl).lineHeight);
    const bottom = caret.offsetTop + caret.offsetHeight - offset;
    if (bottom > pageH - line * 0.6) {
      offset = Math.max(0, caret.offsetTop - pageH / 2);
      textEl.style.transform = `translateY(${-offset}px)`;
    }
  }

  function count(buf) {
    const han = (buf.match(/[一-鿿]/g) || []).length;
    const words = (buf.replace(/[#*]/g, " ").match(/[A-Za-z0-9]+/g) || []).length;
    return han + words;
  }

  // 把脚本拆成按键事件：[文字, 打完后的停顿]
  function steps() {
    const out = [];
    SCRIPT.forEach((para, pi) => {
      if (pi > 0) out.push(["\n\n", 200]);
      for (const piece of para.split("/")) {
        // 英文、数字和 Markdown 标记不经过输入法，一个键一个键地出现
        if (/^[\x20-\x7e]+$/.test(piece)) {
          [...piece].forEach((ch, i) => out.push([ch, i === piece.length - 1 ? 140 : 70]));
          continue;
        }
        let wait;
        if (/^[，。、“”]$/.test(piece)) wait = piece === "。" ? 260 : 120;
        else wait = 120 + piece.length * 55 + Math.random() * 120;
        out.push([piece, wait]);
      }
      out.push(["", 800]);    // 一段写完，停一下
    });
    return out;
  }

  let buf = "", queue = [], timer = null, countTimer = null, running = false;

  function reset() {
    buf = "# ";
    offset = 0;
    textEl.style.transform = "";
    countEl.textContent = "0 字 · 已保存";
    queue = steps();
    queue.splice(0, 2, ["", 900]);   // 新文档自带 “# ”，不用再打
    render(buf);
  }

  function tick() {
    if (!running) return;
    if (!queue.length) {
      timer = setTimeout(() => { reset(); tick(); }, 4000);
      return;
    }
    const [piece, wait] = queue.shift();
    if (piece) {
      buf += piece;
      render(buf);
      // 字数和保存状态在停笔后才更新，不跟着每个字刷新
      clearTimeout(countTimer);
      countTimer = setTimeout(() => { countEl.textContent = `${count(buf)} 字 · 已保存`; }, 700);
    }
    timer = setTimeout(tick, wait);
  }

  function start() { if (!running) { running = true; tick(); } }
  function stop() { running = false; clearTimeout(timer); }

  if (reduced) {
    buf = SCRIPT.join("\n\n").replace(/\//g, "");
    render(buf);
    countEl.textContent = `${count(buf)} 字 · 已保存`;
    return;
  }

  reset();
  // 只在看得见的时候打字
  let inView = false;
  const update = () => (inView && !document.hidden ? start() : stop());
  document.fonts.ready.then(() => {
    new IntersectionObserver(([e]) => { inView = e.isIntersecting; update(); }).observe(demo);
    document.addEventListener("visibilitychange", update);
  });
})();
