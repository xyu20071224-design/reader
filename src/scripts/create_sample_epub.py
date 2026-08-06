#!/usr/bin/env python3
"""Create a deterministic EPUB used for manual and emulator verification."""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path


CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">lingua-reader-sample</dc:identifier>
    <dc:title>The Lantern Library</dc:title>
    <dc:creator>LinguaReader Test</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover" href="cover.svg" media-type="image/svg+xml" properties="cover-image"/>
    <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
    <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
    <item id="three" href="three.xhtml" media-type="application/xhtml+xml"/>
    <item id="css" href="book.css" media-type="text/css"/>
  </manifest>
  <spine>
    <itemref idref="one"/>
    <itemref idref="two"/>
    <itemref idref="three"/>
  </spine>
</package>
"""

NAV = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Contents</title></head>
<body><nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol>
<li><a href="one.xhtml">A Quiet Beginning</a></li>
<li><a href="two.xhtml">The Draft</a></li>
<li><a href="three.xhtml">Looking Forward</a></li>
</ol></nav></body></html>"""

CSS = """h1 { text-align:center; margin:1.4em 0; } p { margin:.7em 0; text-indent:1.5em; }"""

CHAPTERS = {
    "one.xhtml": (
        "A Quiet Beginning",
        [
            "Mira opened the library before sunrise. The old building was quiet, but it never felt empty.",
            "A narrow beam of light crossed the wooden floor. Dust moved slowly through it like silver rain.",
            "She carried a lantern between the shelves and checked every window. Outside, the company of sparrows had already begun its noisy meeting.",
            "Her teacher had given her one simple task: find the book with a blue compass on its cover.",
            "Mira looked carefully at each title. Some books were well-known histories, while others had not been opened for many years.",
            "Running her finger along the shelf, she discovered a small gap behind a dictionary.",
        ],
    ),
    "two.xhtml": (
        "The Draft",
        [
            "Inside the gap was a folded proposal. Someone had drafted a new policy for protecting the library.",
            "The first draft was covered with notes. In this context, draft meant a preliminary version, not a current of cold air.",
            "Mira figured out that the proposal belonged to the former librarian. He wanted students to take part in restoring damaged books.",
            "The plan described how volunteers could record titles, repair covers, and roll out a lending program.",
            "Although the document was unfinished, its purpose was clear. The library should remain useful to the whole town.",
            "Mira placed the proposal on the desk and began writing a careful reply.",
        ],
    ),
    "three.xhtml": (
        "Looking Forward",
        [
            "By noon, several students had arrived. They looked forward to working together.",
            "One student took off his wet coat, while another took a notebook off the table. The same verb appeared in different situations.",
            "Mira explained the policy and showed everyone the unfinished draft. The group discussed each meaning with attention to context.",
            "They agreed to start with the oldest shelf. Every repaired book would carry a small mark showing the date of its restoration.",
            "As evening approached, the lanterns came on one by one. The library no longer seemed silent.",
            "It had become a place where words, people, and new ideas could meet.",
        ],
    ),
}


def chapter(title: str, paragraphs: list[str]) -> str:
    repeated = paragraphs * 5
    body = "\n".join(f"<p>{paragraph}</p>" for paragraph in repeated)
    return (
        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>"
        f"<title>{title}</title><link rel=\"stylesheet\" href=\"book.css\"/>"
        f"</head><body><h1>{title}</h1>{body}</body></html>"
    )


def build(destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w") as epub:
        epub.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        epub.writestr("META-INF/container.xml", CONTAINER)
        epub.writestr("OPS/content.opf", OPF)
        epub.writestr("OPS/nav.xhtml", NAV)
        epub.writestr("OPS/book.css", CSS)
        epub.writestr(
            "OPS/cover.svg",
            """<svg xmlns="http://www.w3.org/2000/svg" width="800" height="1200">
            <rect width="800" height="1200" fill="#2d4238"/><circle cx="400" cy="390" r="150"
            fill="#e2b96f"/><text x="400" y="710" fill="#f7f1e5" font-size="70"
            text-anchor="middle">THE LANTERN</text><text x="400" y="795" fill="#f7f1e5"
            font-size="70" text-anchor="middle">LIBRARY</text></svg>""",
        )
        for path, (title, paragraphs) in CHAPTERS.items():
            epub.writestr(f"OPS/{path}", chapter(title, paragraphs))


if __name__ == "__main__":
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/sample/TheLanternLibrary.epub")
    build(output)
    print(output.resolve())
