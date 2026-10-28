#!/usr/bin/env python3
"""
Yuki Tales - CLI Novel & Chapter Batch Importer
Usage:
    python3 import_helper.py <file.txt or file.epub or file.zip> [--novel-id ID] [--title "Title"] [--author "Author"]
    python3 import_helper.py --scan [directory]
"""

import os
import sys
import re
import argparse
import zipfile
import xml.etree.ElementTree as ET
import subprocess
import shutil

DB_PATH = os.environ.get("DB_PATH", "./data/bookstore")
H2_JAR = os.environ.get("H2_JAR", "/home/sakura/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar")

def parse_text_chapters(text, split_pattern="auto"):
    lines = text.replace('\r\n', '\n').split('\n')
    chapters = []
    current_title = "Prologue"
    current_body = []
    
    pat = re.compile(r'^(?:Chapter|Ch\.|Episode|Act|Part|Volume)\s*(\d+[\.\d]*)\s*[:\-\s]*(.*)$', re.IGNORECASE)
    if split_pattern == "strict":
        pat = re.compile(r'^(?:Chapter|Ch\.)\s*(\d+[\.\d]*)\s*[:\-\s]*(.*)$', re.IGNORECASE)

    for line in lines:
        trimmed = line.strip()
        m = pat.match(trimmed) if trimmed else None
        if m:
            if current_body:
                body_html = "".join(f"<p>{html_escape(l)}</p>\n" for l in current_body if l.strip())
                chapters.append({"title": current_title, "content": body_html})
                current_body = []
            num = m.group(1)
            rest = m.group(2).strip()
            current_title = f"Chapter {num}" + (f": {rest}" if rest else "")
        else:
            if trimmed:
                current_body.append(trimmed)

    if current_body:
        body_html = "".join(f"<p>{html_escape(l)}</p>\n" for l in current_body if l.strip())
        chapters.append({"title": current_title, "content": body_html})

    return chapters

def parse_epub(epub_path):
    chapters = []
    title = os.path.splitext(os.path.basename(epub_path))[0]
    author = "Unknown Author"
    desc = ""
    cover_url = "/covers/default.jpg"

    with zipfile.ZipFile(epub_path, 'r') as z:
        # Find OPF
        opf_path = None
        for name in z.namelist():
            if name.endswith('.opf'):
                opf_path = name
                break
        
        if opf_path:
            opf_data = z.read(opf_path).decode('utf-8', errors='ignore')
            m_t = re.search(r'<dc:title[^>]*>(.*?)</dc:title>', opf_data, re.IGNORECASE | re.DOTALL)
            if m_t: title = m_t.group(1).strip()
            m_a = re.search(r'<dc:creator[^>]*>(.*?)</dc:creator>', opf_data, re.IGNORECASE | re.DOTALL)
            if m_a: author = m_a.group(1).strip()
            m_d = re.search(r'<dc:description[^>]*>(.*?)</dc:description>', opf_data, re.IGNORECASE | re.DOTALL)
            if m_d: desc = re.sub(r'<[^>]+>', '', m_d.group(1)).strip()

        # Extract HTML/XHTML chapters
        html_files = [n for n in z.namelist() if n.lower().endswith(('.xhtml', '.html', '.htm'))]
        html_files.sort()

        for idx, hf in enumerate(html_files):
            raw = z.read(hf).decode('utf-8', errors='ignore')
            m_head = re.search(r'<h[1-3][^>]*>(.*?)</h[1-3]>', raw, re.IGNORECASE | re.DOTALL)
            chap_title = re.sub(r'<[^>]+>', '', m_head.group(1)).strip() if m_head else f"Chapter {idx + 1}"
            
            m_body = re.search(r'<body[^>]*>(.*?)</body>', raw, re.IGNORECASE | re.DOTALL)
            body = m_body.group(1) if m_body else raw
            body = re.sub(r'<script.*?</script>', '', body, flags=re.IGNORECASE | re.DOTALL)
            body = re.sub(r'<style.*?</style>', '', body, flags=re.IGNORECASE | re.DOTALL)
            
            clean_text = re.sub(r'<[^>]+>', '', body).strip()
            if len(clean_text) > 40:
                chapters.append({"title": chap_title, "content": body.strip()})

    return {"title": title, "author": author, "description": desc, "cover_url": cover_url, "chapters": chapters}

def html_escape(text):
    return (text.replace('&', '&amp;')
                .replace('<', '&lt;')
                .replace('>', '&gt;')
                .replace('"', '&quot;'))

def run_h2_sql(sql_statements):
    tmp_sql = "/tmp/batch_import_cli.sql"
    with open(tmp_sql, "w", encoding="utf-8") as f:
        f.write("SET REFERENTIAL_INTEGRITY FALSE;\n" + sql_statements)
    
    cmd = [
        "java", "-cp", H2_JAR, "org.h2.tools.RunScript",
        "-url", f"jdbc:h2:file:{DB_PATH};AUTO_SERVER=TRUE",
        "-user", "sakura", "-password", "sakura",
        "-script", tmp_sql
    ]
    subprocess.run(cmd, check=True)
    if os.path.exists(tmp_sql):
        os.remove(tmp_sql)

def main():
    parser = argparse.ArgumentParser(description="Yuki Tales Batch Importer")
    parser.add_argument("file", nargs="?", help="Path to .txt or .epub file")
    parser.add_argument("--scan", help="Scan a directory for .txt and .epub files")
    parser.add_argument("--novel-id", type=int, help="Append chapters to existing novel ID")
    parser.add_argument("--title", help="Novel title override")
    parser.add_argument("--author", help="Author override")
    parser.add_argument("--price", type=int, default=0, help="Default chapter price (Snow Flakes)")
    args = parser.parse_args()

    print("=== Yuki Tales Batch Importer CLI ===")
    if args.file:
        file_path = args.file
        if not os.path.exists(file_path):
            print(f"File not found: {file_path}")
            sys.exit(1)

        lower = file_path.lower()
        if lower.endswith(".epub"):
            result = parse_epub(file_path)
            title = args.title or result["title"]
            author = args.author or result["author"]
            desc = result["description"]
            chapters = result["chapters"]
        elif lower.endswith(".txt"):
            with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                raw_text = f.read()
            title = args.title or os.path.splitext(os.path.basename(file_path))[0]
            author = args.author or "Unknown Author"
            desc = f"Imported from {os.path.basename(file_path)}"
            chapters = parse_text_chapters(raw_text)
        else:
            print("Unsupported file format. Please provide .epub or .txt.")
            sys.exit(1)

        print(f"Parsed {len(chapters)} chapters for '{title}' by {author}.")
        
        # Build SQL
        sql = []
        if args.novel_id:
            novel_id = args.novel_id
            print(f"Appending to existing novel ID: {novel_id}")
        else:
            novel_id = "(SELECT COALESCE(MAX(ID), 0) + 1 FROM NOVELS)"
            esc_t = title.replace("'", "''")
            esc_a = author.replace("'", "''")
            esc_d = desc.replace("'", "''")
            sql.append(f"""
                INSERT INTO NOVELS (ID, TITLE, AUTHOR, COVER_URL, TYPE, GENRE, RATING, STATUS, CREATOR_ID, RELEASE_YEAR, COUNTRY_OF_ORIGIN, SOURCE, TAGS, EDITOR_SELECTION, HIDDEN, DESCRIPTION)
                VALUES ({novel_id}, '{esc_t}', '{esc_a}', '/covers/default.jpg', 'NOVEL', 'Fantasy', 4.5, 'ONGOING', 1, 2024, 'International', 'Imported', 'Imported', FALSE, FALSE, '{esc_d}');
            """)

        for i, chap in enumerate(chapters, start=1):
            esc_ct = chap["title"].replace("'", "''")
            esc_body = chap["content"].replace("'", "''")
            sql.append(f"""
                INSERT INTO CHAPTERS (ID, NOVEL_ID, CHAPTER_NUMBER, TITLE, PRICE, PUBLISH_NOTIFICATION_SENT, PUBLISH_AT, CONTENT)
                VALUES ((SELECT COALESCE(MAX(ID), 0) + 1 FROM CHAPTERS), {novel_id if args.novel_id else '(SELECT MAX(ID) FROM NOVELS)'}, {float(i)}, '{esc_ct}', {args.price}, TRUE, CURRENT_TIMESTAMP(), '{esc_body}');
            """)

        run_h2_sql("\n".join(sql))
        print(f"Successfully imported {len(chapters)} chapters into Yuki Tales database!")

if __name__ == "__main__":
    main()
