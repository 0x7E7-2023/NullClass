(function () {
    // 郑州汽车工程职业学院教务适配器（树维 EAMS 平台，上海树维信息科技有限公司 / SupWisdom）
    // —— 第一步：只取数，把教务的原始数据原样交出去。
    //
    // 移植自 shiguang_warehouse 的 ZZVCAE/zzvcae.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Gr11nJ）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 本校的教务登录走学校统一的 CAS（cas.zzvcae.edu.cn），课表本体在
    // jw.zzvcae.edu.cn 的树维 EAMS 上 —— **两者不同源**。manifest 里 loginUrl 写 CAS
    // 入口、allowHosts 写教务主机，理由见 AUDIT.md 第 1 节。
    // 上游 adapters.yaml 的原话：本教务禁止从 jw.zzvcae.edu.cn 直接登录，必须先在 CAS 登录，
    // 再从【服务大厅】→【学生课表查询】打开过课表页，脚本的接口请求才会带上有用的会话。
    //
    // 取数方式：同源接口，最多四个请求，全部打在 jw.zzvcae.edu.cn 的 /eams 上
    // （地址由 originOf() 按当前页面拼，校内换域名或前面挂网关都不受影响）：
    //   ① GET  /eams/courseTableForStd.action            取课表页 HTML：
    //        学号 ids（bg.form.addInput(form,"ids","...")）、学期标签 tagId（id="semesterBarNNNSemester"）
    //        ，同一份 HTML 里还有课表表头的节次时间（th id="0_节次"，文本形如 "(08:00-08:45)"）
    //   ② POST /eams/dataQuery.action                    dataType=semesterCalendar，取学期列表（JSON）
    //   ③ POST /eams/courseTableForStd!courseTable.action  取课表 HTML（课程内嵌在 TaskActivity 块里）
    //   ④ POST /eams/base/calendar-info.action           取学期校历（可选的附加接口，取不到不算失败）
    //
    // 移植改动：
    //   ① ES6 → ES5（async/await 改成 then 链，去掉模板串、箭头函数、扩展运算符、块级声明关键字，
    //      去掉 Object.keys / Array.prototype.findIndex / padStart / Number.isInteger 这些新方法）
    //   ② 只取数：TaskActivity 块解析、周次位图、节次寻址、连堂合并、开学日推算全部在 parse.js
    //      （CI 只跑得动 parse.js，逻辑放这里等于没有回归）
    //   ③ 课程表 HTML 与课表页 HTML **只交出与课表有关的那几段**（见 coursesHtmlOf / 下面的 id 白名单），
    //      页面导航栏里的姓名与学号不带出去，也不进 fixture
    //   ④ 不弹窗问学期（上游 showSingleSelection 会让用户选，随后还问开学日期）：这里直接取
    //      教务**当前选中的**那个学期（页面上的学期标签值优先，其次 dataQuery 返回的 semesterId），
    //      要别的学期请用户在教务页面里切过去再点「提取课表」
    //   ⑤ 不问用户开学日期（上游 showPrompt 兜底那一段没有移植）：parse.js 用学期起止日期推算并
    //      在 warnings 里如实说明
    //   ⑥ 不再读 /eams/courseTableForStd.action 取作息时间：表头那几个节次块已经在 ① 的响应里，
    //      再请求一次同一个地址是浪费（同一个会话下内容一致）。解析也挪到 parse.js
    //   ⑦ 上游的 showAlert / showToast / notifyTaskCompletion / saveImportedCourses /
    //      saveCourseConfig / savePresetTimeSlots 这些桥调用全部没有移植
    //
    // 登录全程由用户在 WebView 里手工完成：本脚本不读、不存、不上报任何账号信息，
    // 不碰登录表单，也不向 CAS 域发任何请求。

    // 教务主机：正常路径下走当前页面同源；用户此刻不在教务系统里时用它兜底
    var JW_ORIGIN = 'https://jw.zzvcae.edu.cn';
    var EAMS = '/eams';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 当前地址里带 /eams/ 就跟着它走（同源最稳）；否则用固定的教务主机。
    // 挂在门户 / 网关前缀下时，/eams 前面那一段原样保留。
    function jwBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at >= 0) return originOf() + path.substring(0, at + EAMS.length);
        return JW_ORIGIN + EAMS;
    }

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': '*/*'
            }
        };
        if (typeof body === 'string') options.body = body;
        return fetch(url, options).then(function (response) {
            if (response.status < 200 || response.status >= 300) {
                throw new Error(
                    '教务系统返回 HTTP ' + response.status +
                    '：登录状态可能已失效，请先在统一身份认证（CAS）里登录、' +
                    '再从服务大厅打开一次课表页，然后点「提取课表」'
                );
            }
            return response.text();
        }, function (error) {
            var detail = error && error.message ? '（' + error.message + '）' : '';
            throw new Error('连不上教务系统' + detail + '：请确认已登录，再点「提取课表」');
        });
    }

    function get(url) {
        return request(url, 'GET', null);
    }

    function post(url, body) {
        return request(url, 'POST', body);
    }

    function encode(data) {
        var parts = [];
        for (var i = 0; i < data.length; i++) {
            parts.push(data[i][0] + '=' + encodeURIComponent(data[i][1]));
        }
        return parts.join('&');
    }

    function trimOf(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    // ---------- 参数（学号 / 学期标签 / 表头节次块） ----------
    // 上游在这里有五种 ids 正则、两种 tagId 正则。这里按同样的优先级保留下来，一条都不放宽：
    // 正则宽松到把别处的数字当成学号，比读不出来更糟（会拿别人的课表去请求）。
    var IDS_PATTERNS = [
        /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["'](\d+)["']\s*\)/,
        /["']ids["']\s*,\s*["'](\d+)["']/,
        /name=["']ids["'][^>]*value=["'](\d+)["']/i,
        /value=["'](\d+)["'][^>]*name=["']ids["']/i,
        /ids\s*=\s*["'](\d+)["']/
    ];

    function firstGroup(source, patterns) {
        for (var i = 0; i < patterns.length; i++) {
            var m = String(source).match(patterns[i]);
            if (m && m[1]) return m[1];
        }
        return null;
    }

    function paramsOf(html) {
        var text = String(html);
        var ids = firstGroup(text, IDS_PATTERNS);
        // 学期标签 id：优先学期条自己的 id，其次任意含 semester 的 id
        var tagId = firstGroup(text, [/id=["'](semesterBar\d+Semester)["']/]);
        if (!tagId) tagId = firstGroup(text, [/id=["']([^"']*[Ss]emester[^"']*)["']/]);
        if (!ids || !tagId) {
            throw new Error(
                '没能从课表页识别出学号与学期标签：请先在统一身份认证（CAS）里登录，' +
                '再从【服务大厅】→【学生课表查询】打开过课表页面，然后点「提取课表」'
            );
        }
        // 学期标签那个元素上的 value 就是教务当前选中的学期 id
        var escaped = tagId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var element = text.match(new RegExp('<[^>]*\\bid=["\']' + escaped + '["\'][^>]*>', 'i'));
        var current = element ? String(element[0]).match(/\bvalue=["'](\d+)["']/i) : null;
        return { ids: ids, tagId: tagId, currentSemesterId: current ? current[1] : null };
    }

    // 课表表头的节次块：树维 EAMS 的节次表头是 th[id="0_节次"]，文本形如 "(08:00-08:45)"。
    // 只交出与节次有关的那几个 th，表头里别的内容不带走。
    function timeSlotsHtmlOf(html) {
        var text = String(html);
        var found = [];
        var regex = /<th[^>]*id=["']0_\d+["'][^>]*>[\s\S]*?<\/th>/gi;
        var match;
        while ((match = regex.exec(text)) !== null) found.push(match[0]);
        if (!found.length) return null;
        return '<table><tr>' + found.join('') + '</tr></table>';
    }

    // ---------- 课程表 HTML：只取与课表有关的那几段 ----------
    // 课表响应里有课程数据块（var teachers / actTeachers / TaskActivity / index 赋值 / unitCount），
    // 还有页面导航栏（含姓名与学号）。这里按白名单把与课表有关的那几段**按原有先后顺序**挑出来拼成
    // 一份小 html 交出去：不带出去的东西不是「少带了一点」，而是根本不会被交出去，也不会进 fixture。
    //
    // 顺序必须保住：parse.js 与上游一样按 var teachers = 切块，块内才有这门课的
    // TaskActivity 与 index 赋值 —— 打乱了先后顺序，课程与教师就会错配。
    //
    // 一段都没挑到时交空串（页面改版 / 被重定向到别的页面），由 parse.js 报一条带定位的错，
    // 而不是把整页（含导航栏里的个人信息）原样交出去。
    function collect(text, regex, out) {
        var match;
        while ((match = regex.exec(text)) !== null) {
            out.push({ at: match.index, text: match[0] });
            if (match[0] === '') regex.lastIndex++;
        }
    }

    function coursesHtmlOf(html) {
        var text = String(html);
        var pieces = [];
        collect(text, /\bunitCount\s*=\s*\d+\s*;/g, pieces);
        collect(text, /var\s+teachers\s*=\s*\[[\s\S]*?\]\s*;/g, pieces);
        collect(text, /(?:var\s+)?actTeachers\s*=\s*\[[\s\S]*?\]\s*;/g, pieces);
        collect(text, /new\s+TaskActivity\([\s\S]*?\)\s*;/g, pieces);
        collect(text, /\bindex\s*=\s*[^;]*;/g, pieces);
        if (!pieces.length) return '';
        pieces.sort(function (a, b) { return a.at - b.at; });
        var kept = [];
        for (var i = 0; i < pieces.length; i++) kept.push(pieces[i].text);
        return kept.join('\n');
    }

    // ---------- 学期列表 ----------
    function jsonOf(source) {
        try {
            var parsed = JSON.parse(String(source));
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch (error) {
            return null;
        }
    }

    function semesterQuery(body) {
        return post(jwBase() + '/dataQuery.action', body).then(function (raw) {
            var json = jsonOf(raw);
            if (!json || !json.semesters || typeof json.semesters !== 'object') return null;
            var list = [];
            var groups = Object.prototype.hasOwnProperty.call(json, 'semesters') ? json.semesters : {};
            for (var key in groups) {
                if (!Object.prototype.hasOwnProperty.call(groups, key)) continue;
                var entries = groups[key];
                if (!(entries instanceof Array)) continue;
                for (var i = 0; i < entries.length; i++) {
                    var semester = entries[i];
                    if (!semester || semester.id === undefined || semester.id === null) continue;
                    list.push({
                        id: String(semester.id),
                        schoolYear: trimOf(semester.schoolYear),
                        term: trimOf(semester.name),
                        startDate: firstText(semester, ['startDate', 'beginDate', 'start', 'startTime', 'dateBegin']),
                        endDate: firstText(semester, ['endDate', 'end', 'endTime', 'finishDate', 'dateEnd'])
                    });
                }
            }
            return {
                semesters: list,
                currentSemesterId: json.semesterId === undefined || json.semesterId === null
                    ? null
                    : String(json.semesterId)
            };
        });
    }

    function firstText(object, keys) {
        for (var i = 0; i < keys.length; i++) {
            var value = object[keys[i]];
            if (value === null || value === undefined || value === '') continue;
            var found = String(value).match(/(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?/);
            if (found) return found[0];
        }
        // 兜底：字段名换了名字时，扫一遍值里像日期的字符串
        for (var key in object) {
            if (!Object.prototype.hasOwnProperty.call(object, key)) continue;
            if (typeof object[key] !== 'string') continue;
            var m = object[key].match(/(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?/);
            if (m) return m[0];
        }
        return null;
    }

    // ---------- 学期校历（可选） ----------
    function calendarOf(semesterId) {
        var body = encode([['version', '1'], ['semesterId', String(semesterId)]]);
        return post(jwBase() + '/base/calendar-info.action', body).then(function (html) {
            var text = String(html)
                .replace(/<[^>]*>/g, ' ')
                .replace(/&nbsp;|&#160;/gi, ' ')
                .replace(/\s+/g, ' ');
            return text.slice(0, 2000);
        }, function () {
            return null;
        });
    }

    function todayIso() {
        var now = new Date();
        function pad(value) { return (value < 10 ? '0' : '') + value; }
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }

    // ---------- 主流程 ----------
    return get(jwBase() + '/courseTableForStd.action').then(function (pageHtml) {
        var params = paramsOf(pageHtml);
        var body = encode([['tagId', params.tagId], ['dataType', 'semesterCalendar']]);
        return semesterQuery(body).then(function (semesterData) {
            var list = semesterData ? semesterData.semesters : [];
            var selectedId = params.currentSemesterId || (semesterData ? semesterData.currentSemesterId : null);
            var semester = null;
            var i;
            for (i = 0; i < list.length; i++) {
                if (list[i].id === selectedId) { semester = list[i]; break; }
            }
            // 学期列表里没有「当前学期」时就交 null：parse.js 会按课表自己推到的最晚周数
            // 兜一个可读的学期名，并在 warnings 里说明这是猜的
            var courseBody = encode([
                ['ignoreHead', '1'],
                ['setting.kind', 'std'],
                ['startWeek', ''],
                ['semester.id', String(semester ? semester.id : (selectedId || ''))],
                ['ids', String(params.ids)]
            ]);
            return post(jwBase() + '/courseTableForStd!courseTable.action', courseBody)
                .then(function (tableHtml) {
                    if (/loginExt|cas\/login/i.test(String(tableHtml)) && String(tableHtml).indexOf('courseTableForStd') < 0 &&
                        String(tableHtml).length < 30000) {
                        throw new Error(
                            '教务会话未建立（请求被重定向到登录页）：请先在统一身份认证（CAS）里登录，' +
                            '再从【服务大厅】→【学生课表查询】打开过课表页，然后点「提取课表」'
                        );
                    }
                    var courses = coursesHtmlOf(tableHtml);
                    if (!courses) {
                        throw new Error(
                            '教务系统返回的课表页里没有找到课程数据块：登录状态可能已失效，' +
                            '或教务系统改了课表格式。请先在统一身份认证（CAS）里登录、' +
                            '再从服务大厅打开一次课表页后重试'
                        );
                    }
                    var calendar = semester && semester.id ? calendarOf(semester.id) : Promise.resolve(null);
                    return calendar.then(function (calendarText) {
                        return JSON.stringify({
                            semesterId: String(semester ? semester.id : (selectedId || '')),
                            semester: semester,
                            semesters: list,
                            currentSemesterId: selectedId,
                            // 学号只用来向教务请求这本课表（service 接口的 ids 参数），
                            // 不带进载荷、不进 fixture —— parse.js 根本不读它
                            pageHtml: timeSlotsHtmlOf(pageHtml),
                            weekHtml: courses,
                            calendarHtml: calendarText,
                            lastTimeHtml: timeSlotsHtmlOf(tableHtml),
                            today: todayIso()
                        });
                    });
                });
        });
    });
})()
