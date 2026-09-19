(function () {
    // 成都信息工程大学教务适配器（树维 EAMS 平台）—— 第一步：只取数，把教务的原始数据原样交出去。
    //
    // 移植自 shiguang_warehouse 的 CUIT/cuit_bk_old.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 igugyj(Pfolg)）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //   上游 adapters.yaml：adapter_id "CUIT_02"、「成都信息工程大学教务管理系统」，
    //   import_url "https://jwc.cuit.edu.cn/"。同目录的 CUIT_01 是「本科实践教学平台」
    //   （cuit_bk_new.js，走 https://ywtb.cuit.edu.cn/），与本件不是一套系统，别拿错。
    //
    // 平台：树维 EAMS（/eams/，上海树维信息科技有限公司 / SupWisdom，新开普子公司）。
    //   判据是脚本自己请求的接口路径（courseTableForStd.action / dataQuery.action /
    //   courseTableForStd!courseTable.action），不是上游注释。
    //
    // 移植改动：
    //   ① ES6 → ES5（上游的一串异步流程改成 then 链，去掉模板串、箭头函数与块级声明关键字）
    //   ② 不弹窗问学期（上游 showSingleSelection 让用户从最近 8 个学期里挑）：改成自动取当前学期 ——
    //      优先用学期接口回的 semesterId，其次用课表入口页上那个学期组件（semesterBar…Semester）
    //      的 value，再退到学期列表的最后一项（上游弹窗的默认项就是最后一项）。用户此刻开在
    //      课表页上、他切过的那个学期优先（移植手册 §3 第 1 步）
    //   ③ 地址不再写死 http://jwgl.cuit.edu.cn（上游用绝对 URL，而这个主机与登录域
    //      jwc.cuit.edu.cn 不是同一台）：改成当前页面同源相对路径，前缀取当前地址里的 /eams。
    //      校外走学校网关、校内直连教务，两条路都落回用户实际打开的那台服务器。
    //      由此 allowHosts 留空（前提与风险见 AUDIT.md 第 2 节）
    //   ④ 上游解析学期响应用的动态求值（把响应拼成一段代码再执行）没有移植：改成
    //      JSON.parse，并带一个「按第一个 { 到最后一个 } 截取」的兜底（响应偶尔带 BOM 或包裹文本）
    //   ⑤ 上游丢掉学期起止日期（只留 id 与名字），导致开学日只能靠用户在导入后自己填。
    //      这里把起止日期一起读出来交给 parse.js 定开学日（手册 §4.2/§4.3）
    //   ⑥ 只取数：课表 HTML 原样交出去，周次位图、index 坐标、课程合并全部在 parse.js 里做
    //   ⑦ 上游的 showToast / showAlert / saveImportedCourses / savePresetTimeSlots /
    //      notifyTaskCompletion 这些桥调用全部没有移植
    //   ⑧ 学号只用于构造课表请求（上游同款），不写进输出：fixture 里不会出现学号
    //
    // 取数链（同源，最多三条，全部打在本校教务主机上，地址由 window.location.origin + /eams 拼出来）：
    //   ① GET  /eams/courseTableForStd.action?&sf_request_type=ajax   → 读学号 ids 与当前学期组件
    //   ② POST /eams/dataQuery.action?sf_request_type=ajax            → 学期列表（含起止日期）
    //      body: tagId=<学期组件 id>&dataType=semesterCalendar
    //   ③ POST /eams/courseTableForStd!courseTable.action?sf_request_type=ajax
    //      body: ignoreHead=1&setting.kind=std&startWeek=&semester.id=<学期 id>&ids=<学号>
    //      → 课表页 HTML（课程是内嵌的 TaskActivity JS 块）
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var EAMS = '/eams';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维的上下文路径是 /eams；万一挂在门户 / 网关的前缀下，跟着当前地址里的那一段走
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
    }

    // 本适配器只发**当前页面同源**的相对路径（见 AUDIT.md 第 2 节）：登录域 jwc.cuit.edu.cn
    // 与脚本请求的教务主机不是同一台，所以万一用户是停在登录页/门户上点的「提取课表」，
    // 请求会被应用的白名单闸门拦下 —— 那种情况下给一句能照做的提示，别只报「连不上」。
    function hostHint() {
        var host = String(window.location.hostname || '');
        if (/jwgl|jwxt|jwc/i.test(host)) return '';
        return '。本适配器只请求当前页面的同源地址，若你当前打开的不是教务课表页（例如是学校门户或登录页），' +
            '请先在教务系统里打开课表页再点「提取课表」';
    }

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': '*/*'
            }
        };
        if (typeof body === 'string') options.body = body;
        return fetch(url, options).then(function (response) {
            if (response.status < 200 || response.status >= 300) {
                throw new Error(
                    '教务系统返回 HTTP ' + response.status +
                    '：登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }
            return response.text();
        }, function (error) {
            var detail = error && error.message ? '（' + error.message + '）' : '';
            throw new Error('连不上教务系统' + detail + hostHint() + '：请确认已登录，再点「提取课表」');
        });
    }

    function textOf(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function localTodayIso() {
        var now = new Date();
        var month = now.getMonth() + 1;
        var day = now.getDate();
        return now.getFullYear() + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    // 学期响应是「像 JSON 的 JS 对象字面量」：上游直接用动态求值吃下去。这里不允许动态求值，
    // 所以先按 JSON 读；读不动就按第一个 { 到最后一个 } 截一段再读（去 BOM / 去包裹文本）。
    var BOM = String.fromCharCode(0xFEFF);   // 不把 BOM 字面写进源码，免得它被当成文件头的 BOM

    function looseJsonParse(raw) {
        var source = String(raw === null || raw === undefined ? '' : raw).trim();
        if (source.charAt(0) === BOM) source = source.substring(1).trim();
        if (!source) return null;
        try {
            return JSON.parse(source);
        } catch (e) {
            var from = source.indexOf('{');
            var to = source.lastIndexOf('}');
            if (from < 0 || to <= from) return null;
            try {
                return JSON.parse(source.substring(from, to + 1));
            } catch (e2) {
                return null;
            }
        }
    }

    // 课表入口页：ids 是学号，semesterBar…Semester 是当前学期组件（它的 value 是学期 id）
    function parseEntryParams(html) {
        var source = String(html || '');
        var idsMatch = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["']([0-9]+)["']\s*\)/.exec(source);
        var tagIdMatch = /id=["'](semesterBar[0-9]+Semester)["']/.exec(source);
        if (!tagIdMatch) tagIdMatch = /id=["']([A-Za-z0-9_]*[Ss]emester[A-Za-z0-9_]*)["']/.exec(source);
        var semesterId = null;
        if (tagIdMatch) {
            var tagId = tagIdMatch[1].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            var element = new RegExp('<[^>]*\\bid=["\']' + tagId + '["\'][^>]*>', 'i').exec(source);
            if (element) {
                var value = /\bvalue=["']([0-9]+)["']/i.exec(element[0]);
                semesterId = value ? value[1] : null;
            }
        }
        return {
            studentId: idsMatch ? idsMatch[1] : '',
            tagId: tagIdMatch ? tagIdMatch[1] : '',
            semesterId: semesterId
        };
    }

    // 学期起止日期的字段名各部署不一致（同族 zzvcae 的名单）
    function dateFieldOf(semester, keys) {
        var i;
        for (i = 0; i < keys.length; i++) {
            var value = textOf(semester[keys[i]]);
            if (/^[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}/.test(value)) return value;
        }
        return '';
    }

    var START_KEYS = ['startDate', 'beginDate', 'start', 'startTime', 'dateBegin'];
    var END_KEYS = ['endDate', 'end', 'endTime', 'finishDate', 'dateEnd'];

    // 学期响应：{semesters: {学年: [ {id, name, schoolYear, ...} ]}, semesterId: 当前学期 id}
    function parseSemesters(payload) {
        var list = [];
        var groups = payload && payload.semesters ? payload.semesters : null;
        if (groups && typeof groups === 'object') {
            var years = Object.keys(groups);
            var i;
            for (i = 0; i < years.length; i++) {
                var entries = groups[years[i]];
                if (!(entries instanceof Array)) continue;
                var j;
                for (j = 0; j < entries.length; j++) {
                    var semester = entries[j];
                    if (!semester || semester.id === null || semester.id === undefined) continue;
                    var name = textOf(semester.name);
                    list.push({
                        id: String(semester.id),
                        name: name,
                        schoolYear: textOf(semester.schoolYear) || textOf(years[i]),
                        startDate: dateFieldOf(semester, START_KEYS),
                        endDate: dateFieldOf(semester, END_KEYS)
                    });
                }
            }
        }
        return {
            semesters: list,
            currentId: payload && payload.semesterId !== null && payload.semesterId !== undefined
                ? String(payload.semesterId)
                : null
        };
    }

    // 自动取当前学期：学期接口说的当前学期 → 入口页学期组件的 value → 列表最后一项（上游弹窗的默认项）
    function pickCurrent(semesters, currentId, pageId) {
        var i;
        if (currentId) {
            for (i = 0; i < semesters.length; i++) {
                if (semesters[i].id === currentId) return semesters[i];
            }
        }
        if (pageId) {
            for (i = 0; i < semesters.length; i++) {
                if (semesters[i].id === pageId) return semesters[i];
            }
        }
        if (!semesters.length) return null;
        return semesters[semesters.length - 1];
    }

    // 学期名：教务给了就用教务的，拿不到用「成都信息工程大学 + 学年学期」（手册 §4.7）
    function termLabel(semester) {
        var name = textOf(semester.name);
        if (name && name.indexOf('学期') >= 0) return name;
        var year = textOf(semester.schoolYear);
        if (/^[0-9]{4}$/.test(year)) year = year + '-' + (parseInt(year, 10) + 1);
        var tail = name ? name + '学期' : '';
        if (year && tail) return year + '学年' + tail;
        if (year) return year + '学年';
        return '成都信息工程大学' + tail;
    }

    function requireOk(condition, message) {
        if (!condition) throw new Error(message);
        return true;
    }

    return request(eamsBase() + '/courseTableForStd.action?&sf_request_type=ajax', 'GET', null)
        .then(function (entryHtml) {
            var params = parseEntryParams(entryHtml);
            requireOk(
                params.studentId,
                '没读到学号（课表入口页里没有 ids）：请先在教务系统里打开「课表」页并确认已登录，' +
                '再点「提取课表」；若教务系统改版了请反馈'
            );

            var query = 'tagId=' + encodeURIComponent(params.tagId) + '&dataType=semesterCalendar';
            if (params.semesterId) query += '&value=' + encodeURIComponent(params.semesterId);

            return request(eamsBase() + '/dataQuery.action?sf_request_type=ajax', 'POST', query)
                .then(function (semesterRaw) {
                    var payload = looseJsonParse(semesterRaw);
                    var parsed = payload ? parseSemesters(payload) : { semesters: [], currentId: null };
                    var current = pickCurrent(parsed.semesters, parsed.currentId, params.semesterId);
                    requireOk(
                        current,
                        '教务系统没有返回学期列表（登录状态可能已失效，或教务系统改了学期接口）：' +
                        '请重新登录后再点「提取课表」'
                    );

                    var body = [
                        'ignoreHead=1',
                        'setting.kind=std',
                        'startWeek=',
                        'semester.id=' + encodeURIComponent(current.id),
                        'ids=' + encodeURIComponent(params.studentId)
                    ].join('&');

                    return request(
                        eamsBase() + '/courseTableForStd!courseTable.action?sf_request_type=ajax',
                        'POST',
                        body
                    ).then(function (courseHtml) {
                        requireOk(
                            String(courseHtml || '').length > 0,
                            '教务系统没有返回课表页面（登录状态可能已失效）：请重新登录后再点「提取课表」'
                        );
                        return JSON.stringify({
                            term: {
                                id: current.id,
                                name: termLabel(current),
                                rawName: current.name,
                                schoolYear: current.schoolYear,
                                startDate: current.startDate,
                                endDate: current.endDate
                            },
                            today: localTodayIso(),
                            semesters: parsed.semesters.map(function (item) {
                                return {
                                    id: item.id,
                                    name: termLabel(item),
                                    schoolYear: item.schoolYear,
                                    startDate: item.startDate,
                                    endDate: item.endDate
                                };
                            }),
                            raw: { tableHtml: courseHtml }
                        });
                    });
                });
        });
})()
