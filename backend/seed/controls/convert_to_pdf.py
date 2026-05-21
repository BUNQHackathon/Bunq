"""
Convert SOP markdown files to PDFs using reportlab.
Produces well-formatted, multi-page PDFs suitable for Textract extraction.
"""

import os
import re
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.lib.colors import HexColor, black, white, Color
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    HRFlowable, PageBreak, KeepTogether
)
from reportlab.platypus.flowables import HRFlowable

# Paths
CONTROLS_DIR = os.path.dirname(os.path.abspath(__file__))

FILES = [
    ("bunq-Control-Sanctions-Screening-v2.md", "bunq-Control-Sanctions-Screening-v2.pdf"),
    ("bunq-Control-AML-CFT-Framework.md", "bunq-Control-AML-CFT-Framework.pdf"),
    ("bunq-Control-KYC-Onboarding.md", "bunq-Control-KYC-Onboarding.pdf"),
    ("bunq-Control-Data-Retention.md", "bunq-Control-Data-Retention.pdf"),
    ("bunq-Control-Incident-Response.md", "bunq-Control-Incident-Response.pdf"),
]

# Colour palette (bunq-ish teal/dark)
BRAND_TEAL = HexColor("#00A8A8")
BRAND_DARK = HexColor("#1A2B3C")
LIGHT_GRAY = HexColor("#F5F5F5")
MID_GRAY = HexColor("#CCCCCC")
TABLE_HEADER_BG = HexColor("#1A2B3C")
TABLE_ROW_ALT = HexColor("#EEF6F6")


def make_styles():
    base = getSampleStyleSheet()

    styles = {}

    styles["doc_title"] = ParagraphStyle(
        "doc_title",
        fontName="Helvetica-Bold",
        fontSize=18,
        textColor=BRAND_DARK,
        spaceAfter=4,
        leading=22,
        alignment=TA_LEFT,
    )
    styles["doc_subtitle"] = ParagraphStyle(
        "doc_subtitle",
        fontName="Helvetica",
        fontSize=11,
        textColor=BRAND_TEAL,
        spaceAfter=16,
        leading=14,
        alignment=TA_LEFT,
    )
    styles["h1"] = ParagraphStyle(
        "h1",
        fontName="Helvetica-Bold",
        fontSize=13,
        textColor=white,
        spaceBefore=14,
        spaceAfter=6,
        leading=16,
        leftIndent=0,
    )
    styles["h2"] = ParagraphStyle(
        "h2",
        fontName="Helvetica-Bold",
        fontSize=11,
        textColor=BRAND_DARK,
        spaceBefore=10,
        spaceAfter=4,
        leading=14,
    )
    styles["h3"] = ParagraphStyle(
        "h3",
        fontName="Helvetica-BoldOblique",
        fontSize=10,
        textColor=BRAND_TEAL,
        spaceBefore=8,
        spaceAfter=3,
        leading=13,
    )
    styles["body"] = ParagraphStyle(
        "body",
        fontName="Helvetica",
        fontSize=9,
        textColor=BRAND_DARK,
        spaceBefore=2,
        spaceAfter=4,
        leading=13,
        alignment=TA_JUSTIFY,
    )
    styles["numbered"] = ParagraphStyle(
        "numbered",
        fontName="Helvetica",
        fontSize=9,
        textColor=BRAND_DARK,
        spaceBefore=2,
        spaceAfter=3,
        leading=13,
        leftIndent=14,
        firstLineIndent=-14,
        alignment=TA_JUSTIFY,
    )
    styles["bullet"] = ParagraphStyle(
        "bullet",
        fontName="Helvetica",
        fontSize=9,
        textColor=BRAND_DARK,
        spaceBefore=1,
        spaceAfter=1,
        leading=12,
        leftIndent=18,
        firstLineIndent=-10,
    )
    styles["table_header"] = ParagraphStyle(
        "table_header",
        fontName="Helvetica-Bold",
        fontSize=8,
        textColor=white,
        leading=10,
    )
    styles["table_cell"] = ParagraphStyle(
        "table_cell",
        fontName="Helvetica",
        fontSize=8,
        textColor=BRAND_DARK,
        leading=10,
    )
    styles["table_cell_bold"] = ParagraphStyle(
        "table_cell_bold",
        fontName="Helvetica-Bold",
        fontSize=8,
        textColor=BRAND_DARK,
        leading=10,
    )
    styles["footer"] = ParagraphStyle(
        "footer",
        fontName="Helvetica",
        fontSize=7,
        textColor=HexColor("#888888"),
        alignment=TA_CENTER,
    )
    return styles


def header_footer(canvas, doc):
    canvas.saveState()
    w, h = A4
    # Header bar
    canvas.setFillColor(BRAND_DARK)
    canvas.rect(0, h - 18*mm, w, 18*mm, fill=1, stroke=0)
    canvas.setFillColor(BRAND_TEAL)
    canvas.rect(0, h - 20*mm, w, 2*mm, fill=1, stroke=0)
    canvas.setFillColor(white)
    canvas.setFont("Helvetica-Bold", 9)
    canvas.drawString(15*mm, h - 12*mm, "bunq B.V. — Internal Control Document")
    canvas.setFont("Helvetica", 8)
    canvas.drawRightString(w - 15*mm, h - 12*mm, "CONFIDENTIAL — INTERNAL USE ONLY")
    # Footer
    canvas.setFillColor(BRAND_TEAL)
    canvas.rect(0, 0, w, 10*mm, fill=1, stroke=0)
    canvas.setFillColor(white)
    canvas.setFont("Helvetica", 7)
    canvas.drawString(15*mm, 3.5*mm, "© bunq B.V. — Amstelplein 1, 1096 HA Amsterdam, NL | Licensed by DNB")
    canvas.drawRightString(w - 15*mm, 3.5*mm, f"Page {doc.page}")
    canvas.restoreState()


def parse_table(lines):
    """Parse markdown table lines into a list of row lists."""
    rows = []
    for line in lines:
        if re.match(r"\|[-| :]+\|", line.strip()):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        rows.append(cells)
    return rows


def render_table(rows, styles, col_widths=None):
    """Build a reportlab Table from parsed rows."""
    if not rows:
        return None
    w = 170 * mm
    ncols = len(rows[0])
    if col_widths is None:
        col_widths = [w / ncols] * ncols

    table_data = []
    for i, row in enumerate(rows):
        rendered_row = []
        for j, cell in enumerate(row):
            # Bold if it contains **...**
            text = re.sub(r"\*\*(.*?)\*\*", r"<b>\1</b>", cell)
            if i == 0:
                p = Paragraph(text, styles["table_header"])
            elif "**" in cell or cell.startswith("<b>"):
                p = Paragraph(text, styles["table_cell_bold"])
            else:
                p = Paragraph(text, styles["table_cell"])
            rendered_row.append(p)
        table_data.append(rendered_row)

    t = Table(table_data, colWidths=col_widths, repeatRows=1)

    # Build alternating style
    ts = [
        ("BACKGROUND", (0, 0), (-1, 0), TABLE_HEADER_BG),
        ("ROWBACKGROUND", (0, 1), (-1, -1), [white, TABLE_ROW_ALT]),
        ("GRID", (0, 0), (-1, -1), 0.4, MID_GRAY),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]
    t.setStyle(TableStyle(ts))
    return t


def h1_block(text, styles):
    """Return a coloured header block for section headings."""
    # Use a 1-cell Table as a coloured banner
    p = Paragraph(text, styles["h1"])
    t = Table([[p]], colWidths=[170 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), BRAND_DARK),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
    ]))
    return t


def inline_md(text):
    """Convert inline markdown to basic reportlab XML."""
    # Bold
    text = re.sub(r"\*\*(.*?)\*\*", r"<b>\1</b>", text)
    # Italic (single *)
    text = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<i>\1</i>", text)
    # Backtick code
    text = re.sub(r"`([^`]+)`", r"<font name='Courier'>\1</font>", text)
    # Escape raw ampersands not already in entity
    text = re.sub(r"&(?!#?\w+;)", "&amp;", text)
    return text


def md_to_flowables(md_text, styles):
    lines = md_text.splitlines()
    flowables = []
    i = 0
    title_done = False

    while i < len(lines):
        line = lines[i]

        # Skip the top-level H1 (we handle as title block)
        if line.startswith("# ") and not title_done:
            title_text = line[2:].strip()
            # Split title on —
            parts = title_text.split("—", 1)
            flowables.append(Spacer(1, 6*mm))
            flowables.append(Paragraph(parts[0].strip(), styles["doc_title"]))
            if len(parts) > 1:
                flowables.append(Paragraph(parts[1].strip(), styles["doc_subtitle"]))
            flowables.append(HRFlowable(width="100%", thickness=1.5, color=BRAND_TEAL, spaceAfter=8))
            title_done = True
            i += 1
            continue

        # H2 — section banner
        if line.startswith("## "):
            text = line[3:].strip()
            flowables.append(Spacer(1, 3*mm))
            flowables.append(h1_block(text, styles))
            i += 1
            continue

        # H3 — subsection
        if line.startswith("### "):
            text = line[4:].strip()
            flowables.append(Paragraph(inline_md(text), styles["h3"]))
            i += 1
            continue

        # HR
        if re.match(r"^---+$", line.strip()):
            flowables.append(HRFlowable(width="100%", thickness=0.5, color=MID_GRAY, spaceBefore=4, spaceAfter=4))
            i += 1
            continue

        # Table: detect by starting |
        if line.strip().startswith("|"):
            table_lines = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                table_lines.append(lines[i])
                i += 1
            rows = parse_table(table_lines)
            if rows:
                ncols = len(rows[0])
                # Distribute widths
                if ncols == 2:
                    cw = [55*mm, 115*mm]
                elif ncols == 3:
                    cw = [40*mm, 60*mm, 70*mm]
                elif ncols == 4:
                    cw = [40*mm, 50*mm, 40*mm, 40*mm]
                else:
                    cw = None
                t = render_table(rows, styles, cw)
                if t:
                    flowables.append(Spacer(1, 2*mm))
                    flowables.append(t)
                    flowables.append(Spacer(1, 3*mm))
            continue

        # Numbered list item
        m = re.match(r"^(\d+)\.\s+(.*)", line)
        if m:
            num = m.group(1)
            content = inline_md(m.group(2))
            # Collect continuation lines
            i += 1
            while i < len(lines) and lines[i].startswith("   "):
                content += " " + inline_md(lines[i].strip())
                i += 1
            flowables.append(Paragraph(f"{num}.&nbsp;&nbsp;{content}", styles["numbered"]))
            continue

        # Bullet list item
        if line.strip().startswith("- "):
            content = inline_md(line.strip()[2:])
            i += 1
            while i < len(lines) and lines[i].startswith("   "):
                content += " " + inline_md(lines[i].strip())
                i += 1
            flowables.append(Paragraph(f"&bull;&nbsp;&nbsp;{content}", styles["bullet"]))
            continue

        # Sub-bullet (indented -)
        if re.match(r"^\s{2,}-\s", line):
            content = inline_md(re.sub(r"^\s+-\s", "", line))
            flowables.append(Paragraph(f"&nbsp;&nbsp;&nbsp;&nbsp;&ndash;&nbsp;{content}", styles["bullet"]))
            i += 1
            continue

        # Blank line
        if not line.strip():
            flowables.append(Spacer(1, 2*mm))
            i += 1
            continue

        # Plain paragraph
        if line.strip():
            flowables.append(Paragraph(inline_md(line.strip()), styles["body"]))

        i += 1

    return flowables


def convert(md_path, pdf_path):
    with open(md_path, encoding="utf-8") as f:
        md_text = f.read()

    doc = SimpleDocTemplate(
        pdf_path,
        pagesize=A4,
        leftMargin=20*mm,
        rightMargin=20*mm,
        topMargin=25*mm,
        bottomMargin=18*mm,
        title=os.path.basename(md_path).replace(".md", ""),
        author="bunq B.V. Compliance",
    )

    styles = make_styles()
    flowables = md_to_flowables(md_text, styles)
    doc.build(flowables, onFirstPage=header_footer, onLaterPages=header_footer)

    size = os.path.getsize(pdf_path)
    print(f"  Created: {os.path.basename(pdf_path)}  ({size:,} bytes)")
    return size


if __name__ == "__main__":
    print("Converting SOPs to PDF...\n")
    for md_name, pdf_name in FILES:
        md_path = os.path.join(CONTROLS_DIR, md_name)
        pdf_path = os.path.join(CONTROLS_DIR, pdf_name)
        if not os.path.exists(md_path):
            print(f"  SKIP (not found): {md_name}")
            continue
        try:
            convert(md_path, pdf_path)
        except Exception as e:
            print(f"  ERROR {md_name}: {e}")
            import traceback; traceback.print_exc()
    print("\nDone.")
