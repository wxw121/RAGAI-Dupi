import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


class _FakeEncoding:
    def encode(self, text):
        return list(text)


fake_tiktoken = types.ModuleType("tiktoken")
fake_tiktoken.get_encoding = lambda name: _FakeEncoding()
sys.modules.setdefault("tiktoken", fake_tiktoken)


class _FakeBM25:
    def __init__(self, tokenized):
        self.tokenized = tokenized

    def get_scores(self, query_tokens):
        query = set(query_tokens)
        return [float(len(query.intersection(tokens))) for tokens in self.tokenized]


fake_rank_bm25 = types.ModuleType("rank_bm25")
fake_rank_bm25.BM25Okapi = _FakeBM25
sys.modules.setdefault("rank_bm25", fake_rank_bm25)


class _FakeWorksheet:
    def __init__(self, title="Sheet"):
        self.title = title
        self._rows = []

    def append(self, row):
        self._rows.append(row)

    def iter_rows(self, values_only=True):
        return iter(self._rows)


class _FakeWorkbook:
    _saved = {}

    def __init__(self):
        self.active = _FakeWorksheet("Sheet")
        self._sheets = [self.active]

    @property
    def sheetnames(self):
        return [sheet.title for sheet in self._sheets]

    def __getitem__(self, name):
        return next(sheet for sheet in self._sheets if sheet.title == name)

    def create_sheet(self, title):
        sheet = _FakeWorksheet(title)
        self._sheets.append(sheet)
        return sheet

    def save(self, path):
        self._saved[str(path)] = [(sheet.title, list(sheet._rows)) for sheet in self._sheets]

    def close(self):
        return None


def _fake_load_workbook(path, read_only=True, data_only=True):
    wb = _FakeWorkbook()
    wb._sheets = []
    for title, rows in _FakeWorkbook._saved.get(str(path), []):
        sheet = _FakeWorksheet(title)
        sheet._rows = rows
        wb._sheets.append(sheet)
    return wb


fake_openpyxl = types.ModuleType("openpyxl")
fake_openpyxl.Workbook = _FakeWorkbook
fake_openpyxl.load_workbook = _fake_load_workbook
sys.modules.setdefault("openpyxl", fake_openpyxl)


fake_httpx = types.ModuleType("httpx")
fake_httpx.Client = lambda **kwargs: None
sys.modules.setdefault("httpx", fake_httpx)


fake_minio = types.ModuleType("minio")
fake_minio.Minio = object
sys.modules.setdefault("minio", fake_minio)

fake_docx = types.ModuleType("docx")
fake_docx.Document = lambda path: types.SimpleNamespace(paragraphs=[])
sys.modules.setdefault("docx", fake_docx)

fake_fitz = types.ModuleType("fitz")
fake_fitz.open = lambda path: types.SimpleNamespace(__enter__=lambda self: [], __exit__=lambda self, *args: False)
sys.modules.setdefault("fitz", fake_fitz)

fake_pymupdf4llm = types.ModuleType("pymupdf4llm")
fake_pymupdf4llm.to_markdown = lambda path: ""
sys.modules.setdefault("pymupdf4llm", fake_pymupdf4llm)


fake_pymilvus = types.ModuleType("pymilvus")
fake_pymilvus.Collection = object
fake_pymilvus.CollectionSchema = object
fake_pymilvus.DataType = types.SimpleNamespace(VARCHAR="varchar", FLOAT_VECTOR="float_vector")
fake_pymilvus.FieldSchema = object
fake_pymilvus.connections = types.SimpleNamespace(connect=lambda **kwargs: None)
fake_pymilvus.utility = types.SimpleNamespace(has_collection=lambda name: False)
sys.modules.setdefault("pymilvus", fake_pymilvus)

fake_pymilvus_exceptions = types.ModuleType("pymilvus.exceptions")
fake_pymilvus_exceptions.MilvusException = Exception
sys.modules.setdefault("pymilvus.exceptions", fake_pymilvus_exceptions)
