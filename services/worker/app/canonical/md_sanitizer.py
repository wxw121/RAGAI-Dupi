import re


def sanitize_markdown(text: str) -> str:
    """对统一 Markdown 做轻量清洗。

    该函数位于文档规范化流水线末端，负责把不同解析器输出收敛成稳定格式：
    统一换行、压缩多余空行，并修复 ATX 标题缺少空格的问题，方便后续分块器
    可靠识别标题层级。
    """
    if not text:
        return ""

    s = text.replace("\r\n", "\n").replace("\r", "\n")
    s = re.sub(r"[ \t]+\n", "\n", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    # 保证 ATX 标题的 # 后存在空格，否则 Markdown 渲染器和分块标题识别都会不稳定。
    s = re.sub(r"^(#{1,6})([^\s#].*)$", r"\1 \2", s, flags=re.MULTILINE)
    return s.strip()
