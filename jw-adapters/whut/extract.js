(function () {
    // 武汉理工大学教务适配器（金智教育 jwapp / kcbcxby 模块）—— 取数
    //
    // 移植自 shiguang_warehouse 的 WHUT/whut_01.js（MIT，上游维护者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游快照：main @ e62554a（2026-09-12）
    //
    // 取数方式：同源接口（不抓页面表格，教务改版不影响）。上游脚本请求的四个接口全部保留：
    //   ① POST /jwapp/sys/kcbcxby/modules/dzkz/jcjcx.do     节次字典（DM / MC）
    //   ② GET  /jwapp/sys/homeapp/api/home/currentUser.do   当前登录人（取 userId 当学号）
    //   ③ POST /jwapp/sys/kcbcxby/modules/bjkcb/cxjcs.do    学期校历（XQKSRQ 开学日 / ZZC 总周数）
    //   ④ POST /jwapp/sys/kcbcxby/modules/xskcb/cxxskcb.do  课表行（带 XNXQDM + XH）
    // 四个都在 jwxt.whut.edu.cn 上，也就是 manifest 的 loginUrl 主机；请求一律走**同源相对
    // 路径**，没有第三方域，allowHosts 为空。
    //
    // 与上游的取数差异（把「问用户」换成「自己判断」，见移植手册 §3 第 1 步）：
    //   ① 学年学期：上游弹窗让用户选学年和学期（默认今年 / 第一学期）。这里按本机日期先定一个
    //      最可能的学期，再拿 ③ 的校历核对「今天在不在这个学期里」；不在就把相邻的另外三个
    //      学期也问一遍，挑今天真正落在其中、或者最靠前的那个。四个候选都拿不到校历时，
    //      退回按日期推算，并由 parse.js 把「学期编号是推算的」写进载荷的 warnings。
    //   ② 节次字典拿不到时上游会静默丢掉全部课程；这里照交空数组，由 parse.js 走内置对照表
    //      并写进 warnings。
    //
    // 交出去的是教务的原始行 + 学期元信息，不算周次、不拼课程（那是 parse.js 的活）。
    // 输出里**剔除学号与姓名**（XH / XM）：适配器只做排课信息这一件事，顺带让 fixture 天然脱敏。
    // 键名按**大小写不敏感**比对（见 withoutPersonal）：金智有的部署回小写 xh / xm，
    // 精确匹配会把这几个键原样交出去。与同批 neu 的 extract.js 口径一致。
    var BASE = '/jwapp/sys';

    var PERSONAL = { XH: true, XM: true };

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function intOf(value) {
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 失败一律收敛成 null（HTTP 非 2xx、登录失效时返回的 HTML、网络错误），
    // 由调用方决定是致命还是降级 —— 校历和节次字典拿不到都不该让整次提取失败。
    function request(path, body, method) {
        var headers = {
            'accept': 'application/json, text/javascript, */*; q=0.01',
            'x-requested-with': 'XMLHttpRequest'
        };
        var options = { method: method || 'POST', headers: headers };
        if (body !== null && body !== undefined) {
            headers['content-type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
            options.body = body;
        }
        // 不写 credentials：同源请求 fetch 默认就带 Cookie（上游写的 include 在这里等价）
        return fetch(BASE + path, options).then(function (response) {
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

    // 金智的响应形如 { code: "0", datas: { <键>: { rows: [ 若干行 ] } } }，键名各校不一：
    // 先认已知的键，再退回「第一个带 rows 的键」。
    function rowsOf(json, key) {
        if (!json || !json.datas) return null;
        var datas = json.datas;
        if (typeof datas !== 'object') return null;
        if (key && datas[key]) return datas[key].rows || [];
        for (var name in datas) {
            if (!datas.hasOwnProperty(name)) continue;
            if (datas[name] && datas[name].rows) return datas[name].rows;
        }
        return null;
    }

    // 金智的列表接口常带 totalSize（总条数）。课表接口上游没有分页参数，这里也不猜分页参数，
    // 只把总数交出去 —— 万一教务某天开始分页，parse.js 能据「拿到几条 / 共几条」提醒用户。
    function totalOf(json, key) {
        if (!json || !json.datas || typeof json.datas !== 'object') return null;
        var node = key ? json.datas[key] : null;
        if (!node) return null;
        var total = intOf(node.totalSize);
        if (total === null) total = intOf(node.total);
        return total;
    }

    // currentUser.do 返回 { datas: { userId: 学号 } }（上游口径）。多认几个别名，
    // 但只取「当前登录人」这一个标识，不读其它身份信息。
    function currentUserId(json) {
        if (!json || !json.datas) return '';
        var datas = json.datas;
        if (typeof datas === 'string') return text(datas);
        var names = ['userId', 'userid', 'USERID', 'yhId', 'XH', 'xh'];
        for (var i = 0; i < names.length; i++) {
            var value = text(datas[names[i]]);
            if (value) return value;
        }
        return '';
    }

    // 'yyyy-MM-dd HH:mm:ss' 之类的教务日期 → 'yyyy-MM-dd'；解析不出就 null
    function isoDay(value) {
        var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(text(value));
        return m ? m[1] + '-' + m[2] + '-' + m[3] : null;
    }

    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function addDays(iso, days) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso));
        if (!m) return null;
        var date = new Date(Date.UTC(parseInt(m[1], 10), parseInt(m[2], 10) - 1, parseInt(m[3], 10)));
        var moved = new Date(date.getTime() + days * 86400000);
        return moved.getUTCFullYear() + '-' + pad2(moved.getUTCMonth() + 1) + '-' + pad2(moved.getUTCDate());
    }

    // 拿不到校历时的开学日推算：本机当前周的周一（parse.js 会照用并说明是推算值）
    function recentMonday() {
        var now = new Date();
        var offset = (now.getDay() + 6) % 7;
        var monday = new Date(now.getTime() - offset * 86400000);
        return monday.getFullYear() + '-' + pad2(monday.getMonth() + 1) + '-' + pad2(monday.getDate());
    }

    // 学年按「9 月开学」的惯例切：9 月～次年 8 月是同一个学年
    function academicYearStart(date) {
        return (date.getMonth() + 1 >= 9) ? date.getFullYear() : date.getFullYear() - 1;
    }

    // 按本机日期最可能的那个学期：9 月～次年 1 月是秋季（第一学期），2～8 月是春季（第二学期）
    function preferredTerm(date) {
        var year = date.getFullYear();
        var month = date.getMonth() + 1;
        if (month >= 9) return { xn: year + '-' + (year + 1), xq: '1' };
        if (month === 1) return { xn: (year - 1) + '-' + year, xq: '1' };
        return { xn: (year - 1) + '-' + year, xq: '2' };
    }

    // 候选学期：本学年两个 + 下个学年秋季 + 上个学年春季（覆盖今天前后相邻的学期）。
    // 最可能的那个排在最前面 —— 挑选结果只依赖这个固定顺序，不依赖接口返回的快慢。
    function candidateTerms(date) {
        var start = academicYearStart(date);
        var preferred = preferredTerm(date);
        var preferredCode = preferred.xn + '-' + preferred.xq;
        var all = [
            { xn: start + '-' + (start + 1), xq: '1' },
            { xn: start + '-' + (start + 1), xq: '2' },
            { xn: (start + 1) + '-' + (start + 2), xq: '1' },
            { xn: (start - 1) + '-' + start, xq: '2' }
        ];
        var ordered = [];
        var rest = [];
        var i;
        for (i = 0; i < all.length; i++) {
            all[i].code = all[i].xn + '-' + all[i].xq;
            if (all[i].code === preferredCode) ordered.push(all[i]);
            else rest.push(all[i]);
        }
        for (i = 0; i < rest.length; i++) ordered.push(rest[i]);
        return { preferred: preferred, preferredCode: preferredCode, ordered: ordered };
    }

    // 学期校历：上游口径是「取接口返回的第一行的 XQKSRQ 与 ZZC」
    function fetchCalendar(candidate) {
        var body = 'XN=' + encodeURIComponent(candidate.xn) + '&XQ=' + encodeURIComponent(candidate.xq);
        return request('/kcbcxby/modules/bjkcb/cxjcs.do', body).then(function (json) {
            var list = rowsOf(json, 'cxjcs') || [];
            var row = list.length ? list[0] : null;
            if (!row) return null;
            return {
                code: candidate.code,
                firstDay: isoDay(row.XQKSRQ),
                totalWeeks: intOf(row.ZZC)
            };
        });
    }

    // 「今天在不在这个学期里」：开学日 + 总周数 × 7 天。ISO 日期串的字典序就是时间序。
    function coversToday(info, today) {
        if (!info || !info.firstDay) return false;
        var weeks = info.totalWeeks >= 1 ? info.totalWeeks : 20;
        var last = addDays(info.firstDay, weeks * 7 - 1);
        return !!last && today >= info.firstDay && today <= last;
    }

    // 先问最可能的那个学期；今天落在它的学期里就直接用它，否则把其余候选也问一遍
    function calendarsFor(ordered) {
        return fetchCalendar(ordered[0]).then(function (first) {
            if (first && coversToday(first, todayIso())) return [first];
            var rest = ordered.slice(1);
            var calls = [];
            for (var i = 0; i < rest.length; i++) calls.push(fetchCalendar(rest[i]));
            return Promise.all(calls).then(function (others) {
                var all = [];
                if (first) all.push(first);
                for (var j = 0; j < others.length; j++) {
                    if (others[j]) all.push(others[j]);
                }
                return all;
            });
        });
    }

    // 优先「今天落在里面」的学期；都不覆盖今天（还没开学 / 已经放假）时用最可能的那个。
    // 接口万一忽略 XN/XQ 只回当前学期，四个候选会同解，这里也仍然选中最可能的那个。
    function chooseTerm(ordered, infos, today) {
        var byCode = {};
        var i;
        for (i = 0; i < infos.length; i++) byCode[infos[i].code] = infos[i];
        for (i = 0; i < ordered.length; i++) {
            if (byCode[ordered[i].code] && coversToday(byCode[ordered[i].code], today)) return byCode[ordered[i].code];
        }
        for (i = 0; i < ordered.length; i++) {
            if (byCode[ordered[i].code]) return byCode[ordered[i].code];
        }
        return null;
    }

    function withoutPersonal(list) {
        var out = [];
        for (var i = 0; i < list.length; i++) {
            var src = list[i] || {};
            var dst = {};
            for (var key in src) {
                // 先 toUpperCase() 再查表：教务回 xh / xm（小写）时同样剔除，别让学号姓名漏进载荷
                if (src.hasOwnProperty(key) && !PERSONAL[String(key).toUpperCase()]) dst[key] = src[key];
            }
            out.push(dst);
        }
        return out;
    }

    return Promise.all([
        request('/kcbcxby/modules/dzkz/jcjcx.do', null),
        request('/homeapp/api/home/currentUser.do', null, 'GET')
    ]).then(function (res) {
        var sectionRows = rowsOf(res[0], 'jcjcx') || [];
        var studentId = currentUserId(res[1]);
        var candidates = candidateTerms(new Date());

        return calendarsFor(candidates.ordered).then(function (infos) {
            var chosen = chooseTerm(candidates.ordered, infos, todayIso());
            var term;
            if (chosen) {
                term = {
                    code: chosen.code,
                    name: null,
                    source: 'calendar',
                    firstDay: chosen.firstDay || recentMonday(),
                    firstDaySource: chosen.firstDay ? 'calendar' : 'guess',
                    totalWeeks: chosen.totalWeeks >= 1 ? chosen.totalWeeks : null
                };
            } else {
                term = {
                    code: candidates.preferredCode,
                    name: null,
                    source: 'guess',
                    firstDay: recentMonday(),
                    firstDaySource: 'guess',
                    totalWeeks: null
                };
            }

            // 学号：上游就是拿它当 XH 传给课表接口的。拿不到也照发（有些部署按会话身份取数），
            // 真的取不到课表时下面的报错会把登录状态说清楚。
            var body = 'XNXQDM=' + encodeURIComponent(term.code);
            if (studentId) body += '&XH=' + encodeURIComponent(studentId);

            return request('/kcbcxby/modules/xskcb/cxxskcb.do', body).then(function (json) {
                var list = rowsOf(json, 'cxxskcb');
                if (list === null) {
                    throw new Error('没能取到 ' + term.code + ' 的课表数据：登录状态可能已失效，' +
                        '请在教务页面里重新登录后再点「提取课表」');
                }
                return JSON.stringify({
                    term: term,
                    sectionRows: sectionRows,
                    totalSize: totalOf(json, 'cxxskcb'),
                    rows: withoutPersonal(list)
                });
            });
        });
    });
})()
