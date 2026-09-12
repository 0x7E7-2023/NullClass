(function () {
    // 南京工业职业技术大学教务适配器（金智教育 WIS / jwapp 平台）—— 取数
    //
    // 移植自 shiguang_warehouse 的 NIIT/niit.js（MIT，上游作者 nifs1729）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游快照：main @ e62554a（2026-09-12）
    //
    // 取数方式：同源接口（不抓页面表格，教务改版不影响）。请求只打到 jwxt.niit.edu.cn
    // —— 本校教务域，等于 manifest 的 loginUrl 主机，没有第三方域：
    //   ① 当前学年学期（XNXQDM）：
    //      主路径是上游的做法 —— 读课表页上的 #dqxnxq2 表单值；
    //      读不到（用户停在门户首页）时退到平台通用的
    //      /jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do（同平台的 dlutci 在用）；
    //      再失败才按本机日期推算，推算出来的学期编号会在载荷的 warnings 里说明。
    //   ② 课表行：/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do（上游唯一的请求），带 XNXQDM。
    //      接口按会话里的学生身份取数，脚本不需要学号 —— 整条链路不碰学籍接口
    //      （同平台的 dlutci 要先取一次学生信息拿学号，这里不需要）。
    //   ③ 校历（可选，失败不影响导入）：/jwapp/sys/wdkb/modules/xskcb/cxxljc.do，
    //      取开学日 XQKSRQ 与总周数 ZZC。拿不到就由 parse.js 按「最近的周一」推算并说明。
    //
    // 交出去的是教务的原始行 + 学期元信息，不算周次、不拼课程（那是 parse.js 的活）。
    // 输出里**剔除了学号与姓名**（XH / XM）：适配器只做排课信息这一件事，顺带让 fixture 天然脱敏。
    var BASE = 'https://jwxt.niit.edu.cn/jwapp/sys/wdkb';

    var PERSONAL = { XH: true, XM: true };

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    // 失败一律收敛成 null（HTTP 非 2xx、登录失效时返回的 HTML、网络错误），
    // 由调用方决定是致命还是降级 —— 校历拿不到不该让整次提取失败。
    function postJson(path, body) {
        return fetch(BASE + path, {
            method: 'POST',
            credentials: 'include',
            headers: {
                'content-type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'x-requested-with': 'XMLHttpRequest'
            },
            body: body || ''
        }).then(function (response) {
            if (response.status < 200 || response.status >= 300) return null;
            return response.json().then(function (json) {
                return json;
            }, function () {
                return null;
            });
        }, function () {
            return null;
        });
    }

    // 金智的响应形如 { code: "0", datas: { <键>: { rows: [...] } } }，键名各校不一：
    // 上游的做法是「取第一个带 rows 的键」，这里保持同样的兜底，并优先认已知的键。
    function rowsOf(json) {
        if (!json || !json.datas) return null;
        var datas = json.datas;
        var known = ['xskcb', 'cxxszhxqkb', 'cxxljc', 'dqxnxq'];
        var i;
        for (i = 0; i < known.length; i++) {
            if (datas[known[i]] && datas[known[i]].rows) return datas[known[i]].rows;
        }
        for (var key in datas) {
            if (!datas.hasOwnProperty(key)) continue;
            if (datas[key] && datas[key].rows) return datas[key].rows;
            if (Object.prototype.toString.call(datas[key]) === '[object Array]') return datas[key];
        }
        return null;
    }

    function messageOf(json) {
        if (!json) return '';
        return text(json.msg) || text(json.message);
    }

    function termFromPage() {
        try {
            var el = document.querySelector('#dqxnxq2');
            var value = el && el.getAttribute ? el.getAttribute('value') : null;
            return value ? String(value).replace(/\s+/g, '') : '';
        } catch (e) {
            return '';
        }
    }

    // 上游的日期兜底口径：1~6 月算春季学期（本学年第二学期），7~12 月算秋季学期（第一学期）
    function termFromDate() {
        var now = new Date();
        var year = now.getFullYear();
        if (now.getMonth() + 1 <= 6) return (year - 1) + '-' + year + '-2';
        return year + '-' + (year + 1) + '-1';
    }

    function resolveTerm() {
        var code = termFromPage();
        if (code) return Promise.resolve({ code: code, name: null, source: 'page' });

        return postJson('/modules/jshkcb/dqxnxq.do', '').then(function (json) {
            var rows = rowsOf(json) || [];
            var row = rows.length ? rows[0] : null;
            if (row && row.DM) {
                return { code: String(row.DM), name: text(row.MC) || null, source: 'api' };
            }
            return { code: termFromDate(), name: null, source: 'guess' };
        });
    }

    // 校历按「学年 + 学期」分列（XN = 2026-2027，XQ = 1）；学期编号形如 2026-2027-1
    function findCalendarRow(rows, termCode) {
        var parts = String(termCode).split('-');
        if (parts.length < 3) return null;
        var xn = parts[0] + '-' + parts[1];
        var xq = parts[2];
        for (var i = 0; i < rows.length; i++) {
            if (String(rows[i].XN) === xn && String(rows[i].XQ) === xq) return rows[i];
        }
        return null;
    }

    function isoDay(value) {
        var match = /^(\d{4})-(\d{2})-(\d{2})/.exec(text(value));
        return match ? match[1] + '-' + match[2] + '-' + match[3] : null;
    }

    // 拿不到校历时的开学日推算：本机当前周的周一（parse.js 会照用并在载荷里说明是推算值）
    function recentMonday() {
        var now = new Date();
        var offset = (now.getDay() + 6) % 7;
        var monday = new Date(now.getTime() - offset * 86400000);
        return monday.getFullYear() + '-' + pad2(monday.getMonth() + 1) + '-' + pad2(monday.getDate());
    }

    function withoutPersonal(rows) {
        var out = [];
        for (var i = 0; i < rows.length; i++) {
            var src = rows[i];
            var dst = {};
            for (var key in src) {
                if (src.hasOwnProperty(key) && !PERSONAL[key]) dst[key] = src[key];
            }
            out.push(dst);
        }
        return out;
    }

    return resolveTerm().then(function (term) {
        return Promise.all([
            postJson('/modules/xskcb/cxxszhxqkb.do', 'XNXQDM=' + encodeURIComponent(term.code)),
            postJson('/modules/xskcb/cxxljc.do', '')
        ]).then(function (res) {
            var rows = rowsOf(res[0]);
            if (rows === null) {
                throw new Error('没能取到课表数据' + (messageOf(res[0]) ? '（' + messageOf(res[0]) + '）' : '') +
                    '：登录状态可能已失效，请在教务页面里重新登录后再点「提取课表」');
            }

            var calendar = findCalendarRow(rowsOf(res[1]) || [], term.code);
            var firstDay = calendar ? isoDay(calendar.XQKSRQ) : null;
            var totalWeeks = calendar ? parseInt(calendar.ZZC, 10) : NaN;

            return JSON.stringify({
                term: {
                    code: term.code,
                    name: term.name,
                    source: term.source,
                    firstDay: firstDay || recentMonday(),
                    firstDaySource: firstDay ? 'calendar' : 'guess',
                    totalWeeks: isNaN(totalWeeks) ? null : totalWeeks
                },
                rows: withoutPersonal(rows)
            });
        });
    });
})()
