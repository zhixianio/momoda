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
  const SCRIPT = [
    "# /春天/的/随笔",
    "窗外/的/玉兰/开了/。/我/把/键盘/放在/膝盖上/，/屏幕/安安静静/的/，/只有/字/一个/一个/落下来/。",
    "以前/总在/电脑上/写/，/写着/写着/就去/回消息/、/查资料/、/看一眼/新闻/。/不是/不想写/，/是/到处/都有/东西/在/“/叫/”/你/。",
    "这块/屏幕/不会/叫你/。/光标/不闪/，/页面/不滚/，/写满了/就/翻过去/，/像/翻/一页纸/。",
    "**/慢一点/也/没关系/。/**/墨水/会/等你/。",
    "明天/想/写写/楼下/那棵/银杏/。",
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
      if (pi > 0) out.push(["\n\n", 380]);
      for (const piece of para.split("/")) {
        let wait;
        if (/^[#*\s]+$/.test(piece)) wait = 110 * piece.length;
        else if (/^[，。、“”]$/.test(piece)) wait = piece === "。" ? 520 : 240;
        else wait = 260 + piece.length * 110 + Math.random() * 260;
        out.push([piece, wait]);
      }
      out.push(["", 1700]);   // 一段写完，停下来想一想
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
    queue[0] = ["", 900];       // 新文档自带的 “# ”
    render(buf);
  }

  function tick() {
    if (!running) return;
    if (!queue.length) {
      timer = setTimeout(() => { reset(); tick(); }, 5200);
      return;
    }
    const [piece, wait] = queue.shift();
    if (piece) {
      buf += piece;
      render(buf);
      // 字数和保存状态在停笔后才更新，不跟着每个字刷新
      clearTimeout(countTimer);
      countTimer = setTimeout(() => { countEl.textContent = `${count(buf)} 字 · 已保存`; }, 1200);
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
