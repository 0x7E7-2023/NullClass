(function () {
    // 东北大学教务适配器（金智教育 jwapp / WIS 平台）—— 取数段
    //
    // 移植自 shiguang_warehouse 的 NEU/neu.js（MIT，上游作者 Vera-zero）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游快照：main @ e62554a（2026-09-12）
    //
    // 平台核实（按脚本**实际请求的接口路径**判，不看上游注释、也不看相似度榜）：
    // 上游锚定的接口是 https://jwxt.neu.edu.cn/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do，
    // 响应信封是金智的 { code: "0", datas: { getMyScheduleDetail: { arrangedList: [...] } } }，
    // 行字段是 courseName / dayOfWeek / beginSection / endSection。同平台参照：
    // 上游 CAPU/capadap.js 请求**完全相同**的路径与信封；XJZFU/xjzfu.js 请求
    // homeapp/api/home/student/getMyScheduleDetail.do（同族、字段几乎一致）。
    // 相似度榜上离本件最近的 upc 是**强智** —— 那只是上游脚手架（弹窗 + 桥回调 + 作息表）
    // 造成的文本相似，不是同平台，不要照抄强智的 jsxsd 路子。
    //
    // 全部请求都是**当前页面同源**的相对路径（= manifest 的 loginUrl 主机 jwxt.neu.edu.cn），
    // 没有第二个域，所以 allowHosts 是空数组：
    //   ① GET  /jwapp/sys/homeapp/api/home/kb/xnxq.do          学年学期列表（当前学期 = selected）
    //   ② POST /jwapp/sys/homeapp/api/home/getTermWeeks.do     校历：开学日 + 总周数（尽力而为）
    //   ③ POST /jwapp/sys/kbapp/api/wdkbcx/getMySectionList.do 作息时间（尽力而为）
    //   ④ POST /jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do 课表行（上游唯一的请求，必须成功）
    // ②③ 是移植时按同平台参照补的（上游 CAPU 在同族接口上用同样的接口），任一失败都不影响
    // 导入：开学日拿不到由 parse.js 按最近的周一推算并写进 warnings，作息表拿不到就不写
    // periodTimes（应用会用默认节次表），两者都不会静默。
    //
    // 学期编号只走 ①，拿不到就按日期推算 —— **不再拿「当前登录用户」接口兜底**：
    // 那个接口按设计返回登录人档案（金智 currentUser.do），落在内置适配器的安全红线
    // 「不请求个人信息类接口」的字面上，而它只作降级链第二环、后面本来就有日期推算兜底，
    // 删掉零代价（2026-09-13 安全审查 + 证伪者均判成立）。
    //
    // 上游的交互（手输学年、手选学期、手选校区、问要不要导入考试）全部没有移植：
    //   - 学年 + 学期 → 自动取教务的「当前学期」（上游脚本本来也查当前学期，只是拿它当默认项）；
    //   - 校区 → 上游选校区只用来套一份**写死的作息表**（南湖/浑南），课表请求本身并不带校区
    //     （XQDM 留空才是上游口径）。这里改成用教务自己的节次接口取时间，不再猜测用户在哪；
    //   - 考试 → 见 AUDIT.md「没有移植的东西」，空课载荷里没有考试这个概念。
    //
    // 交出去的是教务的**原始行**加学期元信息：不算周次、不拼课程、不切段（那是 parse.js 的活）。
    // 输出前剔除学号 / 姓名等个人字段，只留排课信息，顺带让 fixture 天然脱敏。
    var PERSONAL = {
        XH: true,
        XM: true,
        XH_ID: true,
        SFZH: true,
        USERID: true,
        USERNAME: true,
        USER_NAME: true,
        STUDENTID: true,
        STUDENTNUMBER: true,
        REALNAME: true
    };

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function isArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]';
    }

    // 金智的信封是 { code: "0", datas: {...} }，但 datas 底下那一层各接口不一样：
    // getTermWeeks / kb/xnxq 的 datas 本身就是数组，getMySectionList 是裸数组，
    // 另外一些接口是 { rows: [...] }。三种都认，认不出就返回空数组。
    function listOf(node) {
        if (isArray(node)) return node;
        if (node && isArray(node.rows)) return node.rows;
        return [];
    }

    function datasOf(json) {
        if (!json || !json.datas) return null;
        return json.datas;
    }

    function messageOf(json) {
        if (!json) return '';
        return text(json.msg) || text(json.message);
    }

    function jsonOrNull(response) {
        if (!response || response.status < 200 || response.status >= 300) return null;
        return response.json().then(function (json) {
            return json;
        }, function () {
            return null;
        });
    }

    // 失败一律收敛成 null（HTTP 非 2xx、登录失效时返回的 HTML、网络错误），
    // 由调用方决定是致命还是降级 —— 校历和作息表拿不到不该让整次提取失败。
    function requestJson(path, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'fetch-api': 'true',
                'x-requested-with': 'XMLHttpRequest'
            }
        };
        if (method === 'POST') {
            options.headers['content-type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
            options.body = body || '';
        }
        return fetch(path, options).then(jsonOrNull, function () {
            return null;
        });
    }

    function getJson(path) {
        return requestJson(path, 'GET', null);
    }

    function postForm(path, body) {
        return requestJson(path, 'POST', body);
    }

    // 上游让用户手输起始学年、手选秋季/春季。这里只在教务接口问不到时才推算，
    // 推算出来的学期编号会在载荷的 warnings 里说明（猜错学期 = 整张课表都错）。
    // 东北大学校历：秋季学期（编号 1）9 月开学，春季学期（编号 2）2~3 月开学，
    // 7~8 月是暑假（按春季学期猜），1 月还在秋季学期里。
    function termFromDate() {
        var now = new Date();
        var y = now.getFullYear();
        var m = now.getMonth() + 1;
        if (m >= 8) return y + '-' + (y + 1) + '-1';
        if (m <= 1) return (y - 1) + '-' + y + '-1';
        return (y - 1) + '-' + y + '-2';
    }

    // ① 学年学期列表：itemCode 是学期编号，itemName 是教务给的学期名，selected 标出当前学期。
    function termFromList() {
        return getJson('/jwapp/sys/homeapp/api/home/kb/xnxq.do').then(function (json) {
            var list = listOf(datasOf(json));
            if (!list.length) return null;
            var picked = null;
            for (var i = 0; i < list.length; i++) {
                var row = list[i] || {};
                var code = text(row.itemCode);
                if (!code) continue;
                if (row.selected === true || String(row.selected) === 'true') {
                    return { code: code, name: text(row.itemName) || null, source: 'api' };
                }
                if (!picked) picked = { code: code, name: text(row.itemName) || null, source: 'api' };
            }
            return picked;
        });
    }

    function resolveTerm() {
        return termFromList().then(function (term) {
            if (term) return term;
            return { code: termFromDate(), name: null, source: 'guess' };
        });
    }

    // 学期周次表：{ serialNumber, startDate, endDate, curWeek }[]。
    // 第 1 周的 startDate 就是开学日（parse.js 会再按 §4.3 回退到周一）。
    function termWeeksOf(json) {
        var list = listOf(datasOf(json));
        if (!list.length) return { firstDay: null, totalWeeks: null };
        var sorted = list.slice().sort(function (a, b) {
            var na = parseInt((a || {}).serialNumber, 10);
            var nb = parseInt((b || {}).serialNumber, 10);
            if (isNaN(na)) na = 0;
            if (isNaN(nb)) nb = 0;
            return na - nb;
        });
        var maxSerial = 0;
        var allNumeric = true;
        for (var i = 0; i < sorted.length; i++) {
            var n = parseInt((sorted[i] || {}).serialNumber, 10);
            if (isNaN(n)) {
                allNumeric = false;
                break;
            }
            if (n > maxSerial) maxSerial = n;
        }
        return {
            firstDay: isoDate((sorted[0] || {}).startDate),
            totalWeeks: allNumeric && maxSerial >= 1 ? maxSerial : sorted.length
        };
    }

    function isoDate(value) {
        var match = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(text(value));
        if (!match) return null;
        return match[1] + '-' + pad2(parseInt(match[2], 10)) + '-' + pad2(parseInt(match[3], 10));
    }

    function sectionsOf(json) {
        var datas = datasOf(json);
        if (!datas) return [];
        if (datas.getMySectionList) return listOf(datas.getMySectionList);
        return listOf(datas);
    }

    // datas.getMyScheduleDetail.arrangedList 是上游读到的位置（CAPU 同）；
    // XJZFU 那一支是 datas.arrangedList / datas 本身就是数组 —— 都认。
    // 返回 null 表示这一次请求整个失败了（和「返回了但没有课」不是一回事）。
    function scheduleRowsOf(json) {
        var datas = datasOf(json);
        if (!datas) return null;
        if (datas.getMyScheduleDetail) return listOf(datas.getMyScheduleDetail.arrangedList || datas.getMyScheduleDetail);
        if (datas.arrangedList) return listOf(datas.arrangedList);
        return listOf(datas);
    }

    function withoutPersonal(rows) {
        var out = [];
        for (var i = 0; i < rows.length; i++) {
            var src = rows[i];
            if (!src || typeof src !== 'object') continue;
            var dst = {};
            for (var key in src) {
                if (!Object.prototype.hasOwnProperty.call(src, key)) continue;
                if (PERSONAL[String(key).toUpperCase()]) continue;
                dst[key] = src[key];
            }
            out.push(dst);
        }
        return out;
    }

    // 上游带 2 次重试 + 2 秒 sleep，这里只给**必须成功**的课表请求留一次立刻重试：
    // 宿主给整段脚本 30 秒，sleep 会把预算烧光（四次请求分两轮并发，正常不到 2 秒）。
    function fetchSchedule(body) {
        var path = '/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do';
        return postForm(path, body).then(function (json) {
            if (scheduleRowsOf(json) !== null) return json;
            return postForm(path, body);
        });
    }

    return resolveTerm().then(function (term) {
        // XQDM 留空是上游口径（带上校区代码反而取不到课表，上游注释里写明了「神秘参数」）；
        // 作息表用同一个 XQDM 查，所以两边看到的是同一套节次。
        var body = 'XNXQDM=' + encodeURIComponent(term.code) + '&XQDM=';

        return Promise.all([
            postForm('/jwapp/sys/homeapp/api/home/getTermWeeks.do', 'termCode=' + encodeURIComponent(term.code)),
            postForm('/jwapp/sys/kbapp/api/wdkbcx/getMySectionList.do', body),
            fetchSchedule(body)
        ]).then(function (res) {
            var rows = scheduleRowsOf(res[2]);
            if (rows === null) {
                throw new Error('没能取到课表数据' + (messageOf(res[2]) ? '（' + messageOf(res[2]) + '）' : '') +
                    '：登录状态可能已失效，请在教务页面里重新登录后再点「提取课表」');
            }

            var weeks = termWeeksOf(res[0]);
            return JSON.stringify({
                term: {
                    code: term.code,
                    name: term.name,
                    source: term.source,
                    firstDay: weeks.firstDay,
                    firstDaySource: weeks.firstDay ? 'termWeeks' : 'guess',
                    totalWeeks: weeks.totalWeeks
                },
                sections: sectionsOf(res[1]),
                rows: withoutPersonal(rows)
            });
        });
    });
})()
