(function () {
    // 徐州医科大学研究生教务（xzhmu.edu.cn）适配器 —— 第一步：取数。
    //
    // 移植自 shiguang_warehouse 的 XZHMU/xzhmu_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 commit e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游适配器名「徐州医科大学研究生教务」（adapters.yaml 里 category=POSTGRADUATE），
    // 登录走学校统一身份认证（authserver.xzhmu.edu.cn），课表在 ehall.xzhmu.edu.cn 一侧。
    //
    // 平台判定说明（重要，别照抄上游注释）：上游脚本**不请求任何接口** —— 全文没有
    // fetch / XMLHttpRequest / WebSocket，它只读页面上已经画好的 table#kb.curriculum。
    // 所以「按脚本实际请求的接口路径确认平台」在这件上无从谈起：上游注释与批次文档都
    // 写「树维 eams」，但脚本自己给不出接口证据（维护者手上也没有账号去打开那台系统）。
    // 能确认的只有一件：课表是一张**真正的 <table>**（不是青果那种 canvas / 图片），
    // 走 DOM 解析即可，不需要 OCR。这一条只影响说明文字，不影响取数。
    // 上游脚本也没有本科生 / 研究生双分支（只有一条 DOM 读取路径），因此不存在
    // 「移植哪个分支、另一个要不要进 warnings」的问题。
    //
    // ⚠️ 厂商名订正（2026-09-16，批次四）：本文件此前写「强智 eams」，是错的。那套
    // 综合教学管理系统是**上海树维信息科技有限公司（SupWisdom，新开普子公司）**的产品，
    // 不是湖南强智科技的产品（实测页脚署名 + 上游同族 8 个脚本均自称「树维」，证据见
    // jw-adapters/masu/extract.js 文件头）。本文件本来就说不清平台、只说是 DOM 读取，
    // 订正后结论不变，只是把厂商名写对。算法一行未动。
    //
    // 移植改动：
    //   ① 上游把「读 DOM + 解析课程 + 算周次 + 合并去重」揉在一个自执行脚本里；这里切成
    //      两段：本文件只把课表读成「格子 + 它在表格里的网格位置 + 格子里的原始字段」，
    //      不算周次、不拼课程 —— 那些全部交给 parse.js（CI 只跑得动 parse.js）。
    //   ② 列号按**网格**算：rowSpan / colSpan 会把后面的格子挤开，拿 td 下标当列号在
    //      合并单元格之后整行都会错位（本批检查表第 3 条禁的就是这个）。
    //   ③ 星期不在这里判：格子自带的星期属性（上游读的 td[w]）与整格文字都原样交出去，
    //      由 parse.js 决定用哪一条；两条都没有时它会进 warnings，不猜。
    //   ④ 上游的教师名提取用 TreeWalker(SHOW_TEXT) 去比 span **元素**，永远比不中，
    //      所以上游的 teacher 恒为空字符串；这里按文档顺序取「周次 span 与地点 span
    //      之间」的文本（教师常常就是一个裸文本节点或 <a>），取不到就留空。
    //   ⑤ 上游只认 <p> 里的内容，格子没有 <p> 时整格课程被静默丢掉；这里没有 <p> 就把
    //      格子里的 .C_kc_subject 自身当一个段落读。
    //   ⑥ 上游一上来弹「导入前请确保…」的确认框、失败再弹一次错误框；这里不发任何
    //      弹窗式拦截，只在**页面上拿不到、又直接决定开学日**的那一个值上问一句
    //      「现在是第几周」（桥不可用 / 用户取消时不问，parse.js 会退回推算并如实写进
    //      warnings）。
    //   ⑦ 新增页面线索：学期名候选（<select> 选中项）与页面上的「第N周」选择器 ——
    //      教务自己写的学期名、页面上显示的周次都比推算准。
    //   ⑧ 上游用 tbody tr 取行；这里用 table.rows（含 thead），并等表格出现（最多 10 秒）——
    //      课表是页面脚本异步画出来的，onPageFinished 时不一定已经有了。
    //
    // 本文件不发起任何网络请求（只读当前页面 DOM），manifest 的 allowHosts 里那一个域名
    // 是为了让**页面自己**在提取期间照常工作，不是给脚本用的，详见 AUDIT.md。
    var TABLE_ID = 'kb.curriculum';
    var WAIT_MS = 10000;
    var POLL_MS = 250;
    var MAX_FRAME_DEPTH = 2;
    var MAX_SEMESTER_LABELS = 10;
    var MAX_WEEK = 30;
    var SUBJECT_CLASS = 'C_kc_subject';
    var SEMESTER_RE = /20\d{2}\s*[-—~至]\s*20\d{2}\s*学年/;
    var WEEK_OPTION_RE = /^第\s*\d{1,2}\s*周$/;

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return text(value).replace(/\s+/g, ' ').trim();
    }

    function todayIso() {
        var now = new Date();
        var pad = function (n) { return (n < 10 ? '0' : '') + n; };
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }

    function hasClass(el, name) {
        var value = ' ' + text(el.className) + ' ';
        return value.indexOf(' ' + name + ' ') >= 0;
    }

    function tableIn(doc) {
        if (!doc) return null;
        try {
            return doc.querySelector('table#' + TABLE_ID);
        } catch (e) {
            return null;
        }
    }

    // 上游只找一层 iframe（课表常在办事大厅的 iframe 里）；这里往下找两层，
    // 跨域 iframe 读 contentDocument 会抛异常，捕掉继续找下一个。
    function findTable(doc, depth) {
        var table = tableIn(doc);
        if (table) return table;
        if (depth <= 0) return null;
        var frames;
        try {
            frames = doc.querySelectorAll('iframe, frame');
        } catch (e) {
            return null;
        }
        for (var i = 0; i < frames.length; i++) {
            var inner = null;
            try {
                inner = frames[i].contentDocument ||
                    (frames[i].contentWindow && frames[i].contentWindow.document);
            } catch (e) {
                inner = null;
            }
            if (!inner || inner === doc) continue;
            var found = findTable(inner, depth - 1);
            if (found) return found;
        }
        return null;
    }

    function waitForTable() {
        return new Promise(function (resolve) {
            var deadline = new Date().getTime() + WAIT_MS;
            var tick = function () {
                var table = findTable(document, MAX_FRAME_DEPTH);
                if (table) {
                    resolve(table);
                    return;
                }
                if (new Date().getTime() >= deadline) {
                    resolve(null);
                    return;
                }
                setTimeout(tick, POLL_MS);
            };
            tick();
        });
    }

    function isHidden(td) {
        if (td.hidden === true) return true;
        if (td.style && td.style.display === 'none') return true;
        return /display\s*:\s*none/i.test(text(td.getAttribute('style')));
    }

    // 教师名：在「周次 span」与「地点 span」之间的节点里取文本。
    // 上游在这里用了 TreeWalker(SHOW_TEXT) 再和 span 元素比相等 —— 文本节点永远不等于
    // 元素，于是收集标志从来不会打开，teacher 恒为 ''。这里按文档顺序走 childNodes，
    // 命中起点 span 时跳过它自己的子树，中途的元素（教师名可能包在 <a> 里）继续下钻。
    function teacherBetween(node, startSpan, endSpan) {
        var out = '';
        var collecting = false;
        var stop = false;
        var walk = function (parent) {
            var kids = parent.childNodes || [];
            for (var i = 0; i < kids.length && !stop; i++) {
                var kid = kids[i];
                if (kid === endSpan) {
                    stop = true;
                    return;
                }
                if (kid === startSpan) {
                    collecting = true;
                    continue;
                }
                if (!collecting) continue;
                if (kid.nodeType === 3) {
                    out += text(kid.nodeValue);
                } else if (kid.nodeType === 1) {
                    walk(kid);
                }
            }
        };
        walk(node);
        return tidy(out).replace(/^[\-—~、,，:：/|]+/, '').replace(/[\-—~、,，:：/|]+$/, '');
    }

    // 一个格子里可能有多个 <p>，一个 <p> 里还可能用空行（<br><br>）分开两门课 ——
    // 上游就是这么切的，这里照搬；每段取其中的 <span> 文字（课名 / 周次 / 地点）。
    function readBlocks(td) {
        var blocks = [];
        var host = null;
        if (hasClass(td, SUBJECT_CLASS)) {
            host = td;
        } else {
            try {
                host = td.querySelector('.' + SUBJECT_CLASS);
            } catch (e) {
                host = null;
            }
        }
        if (!host) return blocks;

        var sources = [];
        var paragraphs = host.querySelectorAll('p');
        var i;
        var j;
        var k;
        if (paragraphs && paragraphs.length) {
            for (i = 0; i < paragraphs.length; i++) sources.push(paragraphs[i]);
        } else {
            sources.push(host);
        }

        for (i = 0; i < sources.length; i++) {
            var html = text(sources[i].innerHTML).replace(/<br\s*\/?>/gi, '\n');
            var chunks = html.split(/\n\s*\n/);
            for (j = 0; j < chunks.length; j++) {
                if (!tidy(chunks[j])) continue;
                var node = document.createElement('div');
                node.innerHTML = chunks[j];
                var spanNodes = node.querySelectorAll('span');
                var spans = [];
                for (k = 0; k < spanNodes.length; k++) {
                    var value = tidy(spanNodes[k].textContent);
                    if (value) spans.push(value);
                }
                var teacher = '';
                if (spanNodes.length >= 3) teacher = teacherBetween(node, spanNodes[1], spanNodes[2]);
                blocks.push({ spans: spans, teacher: teacher });
            }
        }
        return blocks;
    }

    // 格子 + 它在表格里的网格位置。rowSpan / colSpan 会把后面的格子挤开，所以列号必须
    // 自己数着走（拿 td 的下标当列号，遇到合并单元格之后整行都会错位）。
    function readRows(table) {
        var occupied = {};
        var rows = [];
        var trs = table.rows || [];
        for (var r = 0; r < trs.length; r++) {
            var items = trs[r].cells || [];
            var cells = [];
            var col = 0;
            for (var i = 0; i < items.length; i++) {
                var td = items[i];
                while (occupied[r + ':' + col]) col++;
                var rowSpan = td.rowSpan > 1 ? td.rowSpan : 1;
                var colSpan = td.colSpan > 1 ? td.colSpan : 1;
                for (var dr = 0; dr < rowSpan; dr++) {
                    for (var dc = 0; dc < colSpan; dc++) occupied[(r + dr) + ':' + (col + dc)] = true;
                }
                cells.push({
                    col: col,
                    colSpan: colSpan,
                    rowSpan: rowSpan,
                    day: tidy(td.getAttribute('w')),
                    hidden: isHidden(td),
                    text: text(td.textContent),
                    blocks: readBlocks(td)
                });
                col += colSpan;
            }
            rows.push({ row: r, cells: cells });
        }
        return rows;
    }

    // 学期名候选：<select> 里当前选中的那一项 —— 教务自己写的学期名最准
    function readSemesterLabels() {
        var out = [];
        var selects = document.querySelectorAll('select');
        for (var i = 0; i < selects.length && out.length < MAX_SEMESTER_LABELS; i++) {
            var options = selects[i].options || [];
            var selected = options[selects[i].selectedIndex];
            if (selected && SEMESTER_RE.test(tidy(selected.text))) out.push(tidy(selected.text));
        }
        return out;
    }

    // 页面上的「第N周」选择器：整组选项都是「第N周」时才认，当前选中那项就是课表
    // 正在显示的那一周。教务不给开学日期，这一条能让 parse.js 反推得准一些。
    function readPageWeek() {
        var selects = document.querySelectorAll('select');
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
            var number = tidy(selected.text).match(/\d{1,2}/);
            if (number) return parseInt(number[0], 10);
        }
        return null;
    }

    // 页面上没有周次线索时问用户一句 —— 这个值直接决定开学日，猜错了整学期的课都会偏。
    // 桥不在（allowHosts 通配、老版本、非白名单域）或用户取消都返回 null，parse.js 会
    // 退回「最近的周一」并如实写进 warnings。取消是正常结果，不是错误。
    function askCurrentWeek() {
        var caps = window.__ncCapabilities;
        if (!caps || !caps.ask) return Promise.resolve(null);
        return __ncPrompt({
            title: '现在是第几周？',
            message: '课表页没有写现在进行到第几周。填一个数字（1-30），空课才能把开学日期算准；' +
                '不确定就取消，空课会按最近的周一推算并在导入预览里说明。' +
                '如果你正在看的不是本学期的课表，请取消。',
            placeholder: '例如 3',
            maxLength: 2
        }).then(function (value) {
            var n = parseInt(tidy(value), 10);
            if (n >= 1 && n <= MAX_WEEK) return n;
            return null;
        }, function () {
            return null;
        });
    }

    function resolveWeek() {
        var pageWeek = readPageWeek();
        if (pageWeek >= 1 && pageWeek <= MAX_WEEK) {
            return Promise.resolve({ week: pageWeek, source: 'page' });
        }
        return askCurrentWeek().then(function (asked) {
            if (asked) return { week: asked, source: 'ask' };
            return { week: null, source: null };
        });
    }

    return waitForTable().then(function (table) {
        if (!table) {
            __ncError('页面上没有找到课表表格（table#' + TABLE_ID + '）。请确认：① 已登录教务；' +
                '② 已打开「个人课表」页面；③ 已点查询、课表已经显示出来。然后重新点「提取课表」。');
            // 报完错就不再往下走：返回一个不落地的 Promise，别让宿主拿到半截数据
            return new Promise(function () {});
        }
        var rows = readRows(table);
        return resolveWeek().then(function (week) {
            return JSON.stringify({
                url: text(window.location ? window.location.href : ''),
                title: text(document.title),
                // 取数当天的日期。教务不给开学日期，parse.js 只能拿它当推算的基准；
                // 原样交出去（而不是让 parse.js 自己去读系统时间），回归用例才钉得住。
                today: todayIso(),
                tableFound: true,
                currentWeek: week.week,
                currentWeekSource: week.source,
                semesterLabels: readSemesterLabels(),
                rows: rows
            });
        });
    });
})()
