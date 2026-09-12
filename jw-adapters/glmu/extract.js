(function () {
    // 桂林医科大学教务适配器（Struts2 教务平台，ejwc.glmc.edu.cn）
    //
    // 移植自 shiguang_warehouse 的 GLMU/glmu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 xhh李）
    // 上游脚本与同平台的 HBMU/hbmu.js 是逐字克隆（diff 只有四处差异：文件头、URL 主机、
    // 第 1–5 节的作息时间、登录提示语），所以本适配器与 jw-adapters/hbmu 同源同结构；
    // 四处差异在 AUDIT.md §4 逐条核对过，作息时间用的是 GLMU 自己那张表（不是 hbmu 的）。
    // 移植改动：
    //   ① 上游用两个弹窗问「学年」「学期」；这里改成按当前日期推算学期（同平台的 GDUT 就是这么算的），
    //      只有推算出的学期一条课都取不到时，才用 __ncSelect 让用户改选一个学期 —— 不依赖弹窗，
    //      但也不至于在学期算错时让用户彻底没法导入；
    //   ② 上游在脚本里自己拼课程、自己存时间段；这里只把教务原始行交出去，转换全在 parse.js
    //      （CI 会用 Rhino 真跑那一段，塞在这里的逻辑永远没有回归）；
    //   ③ 顺手探一下同平台的排课日期接口（GDUT 用它取开学日）：拿到就把原始 JSON 交出去，
    //      拿不到就交 null，由 parse.js 推算开学日并在载荷里如实说明；
    //   ④ 行里的学号 / 姓名 / 身份证号字段直接丢掉：载荷用不到它们，只取排课信息这一件事。
    //
    //   ⑤ 分页不看总数字段也能取全：上游写的是「取到的条数 >= total 就停」，接口没回 total 时
    //      这个条件永不成立、只能靠 page > 10 兜底（等于没停）；这里改成 —— 回总数就按总数停，
    //      没回总数就按「这一页取满了吗」停（取不满即最后一页），同样最多 10 页。有没有总数、
    //      是不是翻到上限才停，都随载荷交给用户看（见 parse.js 的 warnings）。
    //
    // 取数方式：同源接口，一个必需请求 + 一个尽力而为的探测请求：
    //   POST /xsgrkbcx!getDataList.action   body: xnxqdm=<学年4位+学期2位>&page=N&rows=100
    //        → { total: N, rows: [ { kcmc, teaxms, jxcdmc, zc, xq, jcdm, ... } ] }
    //   GET  /xsgrkbcx!getKbRq.action?xnxqdm=...&zc=1    → 第 1 周七天的日期（探测失败即放弃）
    // 两个都是相对路径，只会打在本校教务域（与 loginUrl 同源）——不请求任何第三方域，故 allowHosts 为空。
    // manifest 里也**不写 scheduleUrlHint**：取数只走接口，用户停在教务站任意一页（登录首页也行）都能提取，
    // 写一个没验证过的课表页路径反而可能把人带到 404。
    //
    // 输出：{ term: { code, source }, today, dateInfo, total, truncated, rows: [...] }
    //   total     —— 接口报的记录总数；没报 / 非正数时为 null（parse.js 据此在载荷里说明取数方式）
    //   truncated —— true 表示翻到了页数上限时下一页还可能存在（课表可能不完整）
    var LIST_URL = '/xsgrkbcx!getDataList.action';
    var DATE_URL = '/xsgrkbcx!getKbRq.action';
    var PAGE_SIZE = 100;          // 上游每页 100 条
    var MAX_PAGES = 10;           // 兜底：接口异常时最多翻 10 页，别把脚本跑飞
    var MAX_LIST_BODY = 2000000;  // 课表响应超过 2M 字符就不认（多半是登录页 HTML）
    var MAX_DATE_BODY = 200000;   // 日期接口的响应超过这个长度就不认
    var CANDIDATE_TERMS = 6;      // 学期改选时给出的候选个数
    var PERSONAL = { xh: true, xm: true, sfzh: true };

    function pad2(value) {
        return (value < 10 ? '0' : '') + value;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    // 学期代码 = 学年起始年份 4 位 + 学期号 2 位（01 第一学期 / 02 第二学期 / 03 短学期）。
    // 这与同平台的 HBMU / GDUT 一致（上游 GLMU 的学期弹窗也是「上学期 / 下学期 / 短学期」三选一）；
    // parse.js 里有一份同样的命名（那份才是最终写进载荷的）。
    function termNameOf(code) {
        var m = /^(\d{4})(\d{2})$/.exec(String(code));
        if (!m) return String(code);
        var suffix = m[2] === '01' ? '第一学期' : (m[2] === '02' ? '第二学期' : (m[2] === '03' ? '短学期' : m[2] + ' 学期'));
        return m[1] + '-' + (parseInt(m[1], 10) + 1) + '学年' + suffix;
    }

    // 按当前日期推算学期：8 月 – 次年 1 月是秋季（<当年>01，1 月算上一学年），2 – 7 月是春季（<去年>02）。
    // 短学期（03）没有可靠的日历边界，只在用户改选时出现。
    function currentTermCode(now) {
        var year = now.getFullYear();
        var month = now.getMonth() + 1;
        if (month >= 8) return year + '01';
        if (month <= 1) return (year - 1) + '01';
        return (year - 1) + '02';
    }

    function previousTermCode(code) {
        var m = /^(\d{4})(\d{2})$/.exec(String(code));
        if (!m) return null;
        var year = parseInt(m[1], 10);
        var no = parseInt(m[2], 10);
        if (no > 1) return year + '0' + (no - 1);
        return (year - 1) + '03';
    }

    // 行里的个人信息字段一律不带出教务系统。
    function withoutPersonal(rows) {
        var out = [];
        for (var i = 0; i < rows.length; i++) {
            var src = rows[i] || {};
            var dst = {};
            for (var key in src) {
                if (Object.prototype.hasOwnProperty.call(src, key) && !PERSONAL[String(key).toLowerCase()]) {
                    dst[key] = src[key];
                }
            }
            out.push(dst);
        }
        return out;
    }

    function jsonOrNull(body, maxLength) {
        if (typeof body !== 'string' || body.length === 0 || body.length > maxLength) return null;
        try {
            return JSON.parse(body);
        } catch (e) {
            return null;
        }
    }

    function ignoreFailure(promise) {
        return promise.then(function (value) { return value; }, function () { return null; });
    }

    function fetchRows(code) {
        var collected = [];
        var total = null;      // 教务报的记录总数；没报（或报了 0 / 非数字）就是 null
        var expected = 0;      // >0 时按总数翻页，否则按「本页取满没有」翻页

        function page(pageNo) {
            return fetch(LIST_URL, {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'Accept': 'application/json, text/javascript, */*; q=0.01',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: 'xnxqdm=' + encodeURIComponent(code) + '&page=' + pageNo + '&rows=' + PAGE_SIZE
            }).then(function (response) {
                if (response.status === 401 || response.status === 403) {
                    throw new Error('教务系统拒绝访问（' + response.status + '）：请先在页面里登录，再点「提取课表」');
                }
                if (response.status < 200 || response.status >= 300) {
                    throw new Error('教务系统返回 HTTP ' + response.status);
                }
                return response.text().then(function (body) {
                    var json = jsonOrNull(body, MAX_LIST_BODY);
                    if (!json || typeof json !== 'object') {
                        throw new Error('教务系统没有返回课表数据：登录状态可能已失效，请在页面里重新登录后再点「提取课表」');
                    }
                    var rows = (json.rows && typeof json.rows.length === 'number') ? json.rows : [];
                    var reported = parseInt(json.total, 10);
                    if (!isNaN(reported) && reported > 0) { total = reported; expected = reported; }
                    for (var i = 0; i < rows.length; i++) collected.push(rows[i]);
                    // 还要不要翻下一页：教务报了总数就按总数算；没报就按「这一页取满了吗」算
                    //（取满 = 后面多半还有，取不满 = 这就是最后一页）。
                    // 上游这里写的是「取到的条数 >= total 就停」，total 为 undefined 时
                    // 该条件永不成立、只能靠 page > 10 兜底 —— 也就是说上游在「没回 total」的响应下
                    // 反而一路翻到第 10 页把数据取全了；不能比它更差。
                    var more = expected > 0 ? (collected.length < expected && rows.length > 0) : (rows.length >= PAGE_SIZE);
                    if (!more) return { rows: collected, total: total, truncated: false };
                    if (pageNo >= MAX_PAGES) return { rows: collected, total: total, truncated: true };
                    return page(pageNo + 1);
                });
            }, function () {
                throw new Error('连不上教务系统：登录状态可能已失效，请在页面里重新登录后再点「提取课表」');
            });
        }

        return page(1);
    }

    // 同平台（GDUT）用这个接口取开学日。这所学校的部署上不一定有：
    // 任何失败（404 / 登录页 / 不是 JSON / 超长响应）都只返回 null，不影响主流程。
    function fetchDateInfo(code) {
        var url = DATE_URL + '?xnxqdm=' + encodeURIComponent(code) + '&zc=1';
        var request = fetch(url, {
            method: 'GET',
            credentials: 'include',
            headers: { 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            if (response.status < 200 || response.status >= 300) return null;
            return ignoreFailure(response.text()).then(function (body) { return jsonOrNull(body, MAX_DATE_BODY); });
        });
        return ignoreFailure(request);
    }

    function gather(code) {
        return Promise.all([fetchRows(code), fetchDateInfo(code)]).then(function (result) {
            return {
                code: code,
                rows: result[0].rows,
                total: result[0].total,
                truncated: result[0].truncated,
                dateInfo: result[1]
            };
        });
    }

    // 取不到课时才问用户：学期是推算的，教务里又没有「当前学期」接口，这是唯一的补救途径。
    // 桥不在（旧版应用 / 页面域未知）或被用户取消时一律 resolve null，退回推算结果 —— 不弹窗也能用。
    function askTermCode(codes) {
        if (typeof __ncCapabilities === 'undefined' || !__ncCapabilities || !__ncCapabilities.ask) {
            return Promise.resolve(null);
        }
        if (typeof __ncSelect !== 'function') return Promise.resolve(null);
        var items = [];
        for (var i = 0; i < codes.length; i++) items.push(termNameOf(codes[i]));
        return __ncSelect({
            title: '选择要导入的学期',
            message: '按当前日期推算的学期里没有取到课表。请选择你要导入的学期：',
            items: items,
            defaultIndex: 0
        }).then(function (index) {
            if (typeof index !== 'number' || index < 0 || index >= codes.length) return null;
            return index;
        }, function () {
            return null;
        });
    }

    function finish(result, source) {
        if (!result.rows.length) {
            throw new Error(
                '教务系统里「' + termNameOf(result.code) + '」没有取到课表数据：可能是该学期课表还没发布，' +
                '也可能学期推算得不对。请在教务页面里确认能查到你的课表后再点「提取课表」。'
            );
        }
        return JSON.stringify({
            term: { code: result.code, source: source },
            today: isoOf(now),
            dateInfo: result.dateInfo,
            total: result.total,
            truncated: result.truncated === true,
            rows: withoutPersonal(result.rows)
        });
    }

    var now = new Date();
    var primary = currentTermCode(now);
    var candidates = [primary];
    var walk = primary;
    for (var i = 1; i < CANDIDATE_TERMS; i++) {
        walk = previousTermCode(walk);
        if (!walk) break;
        candidates.push(walk);
    }

    return gather(primary).then(function (first) {
        if (first.rows.length > 0) return finish(first, 'auto');
        return askTermCode(candidates).then(function (index) {
            if (index === null) return finish(first, 'auto');
            if (candidates[index] === primary) return finish(first, 'user');
            return gather(candidates[index]).then(function (second) { return finish(second, 'user'); });
        });
    });
})()
