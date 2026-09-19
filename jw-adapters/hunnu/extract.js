(function () {
    // 湖南师范大学教务适配器（树维 EAMS 平台，路径 /eams/；上海树维信息科技有限公司
    // SupWisdom，新开普子公司 —— 不是强智）—— 第一步：取数。
    //
    // 移植自 shiguang_warehouse 的 HUNNU/hunnu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 EarOfWheat）
    //   上游快照 commit e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 本文件不发起任何网络请求（没有 fetch / XMLHttpRequest / sendBeacon / WebSocket），
    // manifest 的 allowHosts 是空数组。它只把「课表页的 HTML 字符串」原样交出去：课程数据是
    // 内嵌在页面里的一段 JS 文本（new TaskActivity(...)），在这个上下文里拿不到也不该由这里
    // 解析 —— 解析全部交给 parse.js（CI 只跑得动 parse.js，这段逻辑第一次有了真回归）。
    //
    // 移植改动（逐条）：
    //   ① 上游把「取 HTML + 解析课程 + 算周次 + 合并节次」揉在一个自执行脚本里；这里切成两段，
    //      本文件只交 HTML 字符串与几条页面线索，不解析课程、不算周次、不判断教师。
    //   ② 上游用 Array.from 加箭头函数回调找 iframe；这里改成 ES5 索引循环 + 自写的
    //      containsToken 包含判断。
    //   ③ 上游用 await sleep(200) 轮询 30 次；这里用 Promise + setTimeout 递归，并且
    //      **认输时也把最后一次读到的内容交出去**（上游直接抛错、把已经拿到的东西全丢掉）。
    //      轮询放宽到 60 次 × 200ms（课表 iframe 是异步拉出来的，慢的时候 6 秒不够）。
    //   ④ 上游找不到课表页时调 window.shiguangBridgePromise.showAlert 弹窗问用户；
    //      我们的脚本不依赖提问 —— 改为 __ncError 明确说明「请先点开『我的课表』」。
    //   ⑤ 上游点 a[href*="courseTableForStd"][target*="eams-iframe"] 把课表 iframe 调出来；
    //      这里保留，它是移植手册 §5 第 7 条明确允许的例外（「上游切到课表页这种点击」），
    //      AUDIT.md 里写清了点的是什么、为什么。除此之外不点击页面上任何元素。
    //   ⑥ 新增页面线索交给 parse.js：今天的日期（开学日只能推算，需要基准）、页面里的
    //      unitCount、学期名候选、以及「第N周」选择器（有它就能反推开学日，比最近的周一准）。
    var IFRAME_SEL = 'iframe.eams-iframe';
    var LINK_SEL = 'a[href*="courseTableForStd"][target*="eams-iframe"]';
    var MAX_POLLS = 60;
    var POLL_MS = 200;
    var IFRAME_READ_TRIES = 15;
    var MAX_SEMESTER_LABELS = 10;
    var SEMESTER_RE = /20\d{2}\s*[-—~]\s*20\d{2}\s*学年/;
    var WEEK_OPTION_RE = /^第\s*\d{1,2}\s*周$/;

    var SPACE_RE = new RegExp('\\s+', 'g');
    var UNIT_COUNT_RE = new RegExp('unitCount\\s*=\\s*(\\d{1,3})\\s*;');
    var WEEK_NUMBER_RE = new RegExp('\\d{1,2}');

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return text(value).replace(SPACE_RE, ' ').trim();
    }

    function containsToken(value, token) {
        return text(value).indexOf(token) >= 0;
    }

    // 元素的属性：优先 getAttribute（拿到的是原样字符串），退回同名属性值。
    function attrOf(el, name) {
        if (!el) return '';
        var viaAttr = el.getAttribute ? text(el.getAttribute(name)) : '';
        if (viaAttr) return viaAttr;
        return text(el[name]);
    }

    // 课表 iframe：src（或 data-src）里带 courseTableForStd 的那一个。
    function findCourseIframe() {
        var frames;
        try {
            frames = document.querySelectorAll(IFRAME_SEL);
        } catch (e) {
            return null;
        }
        for (var i = 0; i < frames.length; i++) {
            var frame = frames[i];
            if (containsToken(attrOf(frame, 'src'), 'courseTableForStd')) return frame;
            if (containsToken(attrOf(frame, 'data-src'), 'courseTableForStd')) return frame;
        }
        return null;
    }

    function iframeHtmlOnce(frame) {
        try {
            var doc = frame.contentDocument;
            if (doc && doc.documentElement) return text(doc.documentElement.outerHTML);
        } catch (e) {
            return '';
        }
        return '';
    }

    // iframe 里的课表 HTML：优先 srcdoc（内联文档），否则等 contentDocument 可读。
    // 跨域 / 读不到时返回空串，由调用方决定是报错还是降级，这里不抛。
    function readIframeHtml(frame) {
        if (!frame) return Promise.resolve('');
        var srcdoc = frame.getAttribute ? text(frame.getAttribute('srcdoc')) : '';
        if (srcdoc) return Promise.resolve(srcdoc);
        var first = iframeHtmlOnce(frame);
        if (first) return Promise.resolve(first);
        return new Promise(function (resolve) {
            var settled = false;
            var tries = 0;
            var finish = function (html) {
                if (settled) return;
                settled = true;
                resolve(html);
            };
            var tick = function () {
                if (settled) return;
                var html = iframeHtmlOnce(frame);
                if (html) {
                    finish(html);
                    return;
                }
                tries++;
                if (tries >= IFRAME_READ_TRIES) {
                    finish('');
                    return;
                }
                setTimeout(tick, POLL_MS);
            };
            try {
                frame.addEventListener('load', function () { tick(); }, { once: true });
            } catch (e) {
                // 老 WebView 认不了 { once: true } 也没关系：tick 自己会收敛
            }
            tick();
        });
    }

    // 三条策略，与上游逐条一致：
    //   1. 当前就已经是课表页（URL 含 courseTableForStd）→ 直接取本文档的 outerHTML；
    //   2. 外层页面里已经有课表 iframe → 取 srcdoc / contentDocument；
    //   3. 都还没有 → 点一下「我的课表」链接（target=eams-iframe）把 iframe 调出来，再轮询等它出现。
    function waitForCourseHtml() {
        var url = text(window.location ? window.location.href : '');
        if (containsToken(url, 'courseTableForStd')) {
            return Promise.resolve({
                html: text(document.documentElement ? document.documentElement.outerHTML : ''),
                source: 'document',
                clicked: false,
                frameFound: true
            });
        }
        var existing = findCourseIframe();
        if (existing) {
            return Promise.resolve({ frame: existing, source: 'iframe', clicked: false, frameFound: true });
        }
        var link = null;
        var clicked = false;
        try {
            link = document.querySelector(LINK_SEL);
        } catch (e) {
            link = null;
        }
        if (link && link.click) {
            link.click();
            clicked = true;
        }
        return new Promise(function (resolve) {
            var tries = 0;
            var tick = function () {
                var frame = findCourseIframe();
                tries++;
                if (frame || tries >= MAX_POLLS) {
                    resolve({ frame: frame, source: 'iframe', clicked: clicked, frameFound: !!frame });
                    return;
                }
                setTimeout(tick, POLL_MS);
            };
            tick();
        });
    }

    // 页面里的 var unitCount = N（本文档的线索；课表 iframe 里那份由 parse.js 自己扫）
    function readUnitCount() {
        var html = document.documentElement ? text(document.documentElement.innerHTML) : '';
        var match = html.match(UNIT_COUNT_RE);
        if (!match) return null;
        var count = parseInt(match[1], 10);
        return count > 0 ? count : null;
    }

    // 学期名候选：课表页工具栏里写的「20xx-20xx学年 第一学期」。教务自己写的名字最准。
    function readSemesterLabels() {
        var out = [];
        var nodes;
        try {
            nodes = document.querySelectorAll('a, span, option, h1, h2, h3');
        } catch (e) {
            return out;
        }
        for (var i = 0; i < nodes.length && out.length < MAX_SEMESTER_LABELS; i++) {
            var value = tidy(nodes[i].textContent);
            if (!value || value.length > 60) continue;
            if (!SEMESTER_RE.test(value)) continue;
            if (out.indexOf(value) >= 0) continue;
            out.push(value);
        }
        return out;
    }

    // 页面上的「第N周」选择器：整组选项都是「第N周」时才认，当前选中那项就是页面上显示的周次。
    // 教务不给开学日期，这一条能让 parse.js 反推得准一些（拿不到就退回「最近的周一」）。
    function readCurrentWeek() {
        var selects;
        try {
            selects = document.querySelectorAll('select');
        } catch (e) {
            return null;
        }
        for (var i = 0; i < selects.length; i++) {
            var options = selects[i].options || [];
            if (options.length < 2) continue;
            var matched = 0;
            for (var j = 0; j < options.length; j++) {
                if (WEEK_OPTION_RE.test(tidy(options[j].text))) matched++;
            }
            if (matched !== options.length) continue;
            var selected = options[selects[i].selectedIndex];
            if (!selected) continue;
            var number = tidy(selected.text).match(WEEK_NUMBER_RE);
            if (number) return parseInt(number[0], 10);
        }
        return null;
    }

    function todayIso() {
        var now = new Date();
        var pad = function (n) { return (n < 10 ? '0' : '') + n; };
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }

    return waitForCourseHtml().then(function (found) {
        // 拿到 iframe 就读它；iframe 存在但读不出内容（跨域 / 还没 load 完）时也往下走，
        // 由 parse.js 按「HTML 里有没有 TaskActivity」决定是报错还是出 warnings。
        if (found.source === 'document') {
            return {
                html: found.html,
                source: 'document',
                clicked: false,
                frameFound: true,
                url: text(window.location ? window.location.href : ''),
                title: text(document.title)
            };
        }
        return readIframeHtml(found.frame).then(function (html) {
            return {
                html: html,
                source: 'iframe',
                clicked: found.clicked,
                frameFound: found.frameFound,
                url: text(window.location ? window.location.href : ''),
                title: text(document.title)
            };
        });
    }).then(function (result) {
        if (!tidy(result.html)) {
            throw new Error(
                '没能读到课表页。请先点开左侧的「我的课表」（让课表显示出来），再点「提取课表」'
            );
        }
        result.today = todayIso();
        result.unitCount = readUnitCount();
        result.currentWeek = readCurrentWeek();
        result.semesterLabels = readSemesterLabels();
        return JSON.stringify(result);
    });
})()
