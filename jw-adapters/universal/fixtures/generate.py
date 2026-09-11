"""一次性生成 jw-adapters/universal 的 fixtures（合成几何，非真实抓取）。

课表页的文本框几何按真实教务表格的样子摆：表头一行星期、首列节次、格子里
课名/教师/周次/教室各占一行。生成两份：
  - dom-table：容器内 44 个文本框 + 整页额外 4 个页头页脚 → parse 应挑容器
  - image-schedule：整页只有 5 个菜单文本 + 一张课表图片 → parse 应退回图片 OCR
"""
import json
import os

OUT = os.path.join("jw-adapters", "universal", "fixtures")
DAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
PERIODS = ["1-2", "3-4", "5-6", "7-8", "9-10"]
DAYS_X = 100
COL_W = 115
ROW_H = 80
ROW0 = 40

# (row, col) -> [课名, 教师?, 周次, 教室]
CELLS = {
    (0, 0): ["高等数学A(一)", "王强", "1-16周", "教1-101"],
    (0, 2): ["大学英语(二)", "李梅", "1-16周", "外语楼305"],
    (0, 4): ["线性代数", None, "1-8周", "教2-201"],
    (1, 1): ["大学物理", "赵磊", "1-16周", "实验楼404"],
    (2, 3): ["数据结构", None, "1-22周", "计算中心A"],
    (2, 5): ["体育(三)", None, "1-16周", "体育馆"],
    (3, 0): ["程序设计基础", "陈静", "2-16双周", "机房302"],
    (3, 6): ["中国近现代史纲要", None, "1-8周", "教3-201"],
    (4, 2): ["概率论与数理统计", "孙芳", "9-16周", "教1-105"],
}


def box(text, x, y, w, h):
    return {"text": text, "x": x, "y": y, "w": w, "h": h}


def container_boxes():
    boxes = []
    for i, day in enumerate(DAYS):
        boxes.append(box(day, DAYS_X + COL_W * i + 10, 6, 32, 20))
    for i, label in enumerate(PERIODS):
        boxes.append(box(label, 30, ROW0 + ROW_H * i + 20, 24, 18))
    for (row, col), cell in sorted(CELLS.items()):
        x = DAYS_X + COL_W * col + 10
        y = ROW0 + ROW_H * row + 6
        name, teacher, weeks, room = cell
        # 四行与行锚点（节次标注的中心）的距离都要在行容差（行距的一半 = 40px）以内，
        # 否则整行会被判成「落不进网格」
        boxes.append(box(name, x, y, 64, 18))
        if teacher:
            boxes.append(box(teacher, x, y + 16, 40, 18))
        boxes.append(box(weeks, x, y + 32, 56, 16))
        boxes.append(box(room, x, y + 48, 72, 16))
    return boxes


def page_chrome():
    return [
        box("首页 选课 成绩 查询", 20, 20, 200, 20),
        box("2026-2027学年第一学期", 420, 20, 240, 20),
        box("同学 张三", 1040, 20, 90, 20),
        box("版权所有 © 教务处", 20, 2560, 160, 18),
    ]


def write(name, data):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("wrote", path)


def main():
    os.makedirs(OUT, exist_ok=True)
    boxes = container_boxes()
    page = {
        "width": 1200,
        "height": 2600,
        "boxes": page_chrome() + [
            dict(b, x=b["x"] + 100, y=b["y"] + 300) for b in boxes
        ],
    }
    container = {"width": 900, "height": 460, "boxes": boxes}
    dom_table = {
        "url": "https://jw.example.edu.cn/xkcx/kb",
        "title": "2026-2027学年第一学期学生课表",
        "page": page,
        "container": container,
        "image": None,
    }
    write("dom-table.extracted.json", dom_table)
    write(
        "dom-table.expected.json",
        {
            "specVersion": 1,
            "kind": "boxes",
            "ocrAssisted": False,
            "pageWidth": container["width"],
            "pageHeight": container["height"],
            "boxes": container["boxes"],
        },
    )

    image_page = {
        "url": "https://jw.example.edu.cn/kb",
        "title": "学生课表",
        "page": {
            "width": 900,
            "height": 1400,
            "boxes": [
                box("首页", 20, 20, 40, 20),
                box("选课", 80, 20, 40, 20),
                box("成绩查询", 140, 20, 80, 20),
                box("教学安排", 20, 120, 80, 20),
                box("版权所有 © 教务处", 20, 1360, 160, 18),
            ],
        },
        "container": None,
        "image": {"url": "https://jw.example.edu.cn/kb.png", "hint": "课表图片"},
    }
    write("image-schedule.extracted.json", image_page)
    write(
        "image-schedule.expected.json",
        {
            "specVersion": 1,
            "kind": "image",
            "ocrAssisted": True,
            "images": [{"url": "https://jw.example.edu.cn/kb.png", "hint": "课表图片"}],
        },
    )


main()
