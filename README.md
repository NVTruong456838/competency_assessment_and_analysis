# TSA/HSA Crawler va Question Bank

## 1. Muc dich

Du an thu thap trang thong tin tu cac nguon tuyen sinh, tai va parse tai lieu PDF, sau do nhan dien cau hoi co cau truc de tao Question Bank dang JSON. He thong tach rieng du lieu raw, du lieu processed, metadata crawl va bao cao validation.

Luong xu ly chinh cho PDF TSA:

```text
PDF cong khai / PDF local
	-> crawler/data/raw/tsa/
	-> DocumentParser (pypdf + PyMuPDF page renders)
	-> QuestionExtractor
	-> tsa_extracted_questions.json
	-> QuestionNormalizer
	-> Normalized Question Bank
```

Pipeline tao ca extracted records va Question Bank da chuan hoa. Vung co anh/ve vector duoc cat theo ranh gioi cau hoi hoac cue bang/so do, roi gan tham chieu vao cau; trang scan/khong co text duoc dua vao danh sach can OCR/review.

Crawler HTML HUST la luong rieng: no luu metadata trang vao `crawler/output/tsa_documents.json`. Cac trang HTML duoc parse thanh Reference Documents, nhung khong tu dong tro thanh cau hoi neu khong co cau hoi va lua chon theo mau ma extractor nhan dang.

### Chuc nang va luong xu ly

Chuong trinh gom cac luong doc lap, co the chay online de thu thap tai lieu cong khai hoac chay local tren PDF da co san:

1. **Thu thap metadata nguon:** `crawler.sources.hust_tsa` tim trang thong tin TSA tren HUST; `crawler.sources.vnu_hsa` tim metadata tai lieu HSA cong khai. Crawler gioi han domain, kiem tra `robots.txt` va ghi URL/tieu de/loai tai lieu vao `crawler/output/`.
2. **Tai va luu tai lieu:** `DocumentDownloader` chi tai dinh dang duoc ho tro, kiem tra Content-Type va response, tinh SHA-256, ghi file an toan vao `crawler/data/raw/<exam>/`. File raw la dau vao goc, can duoc giu lai; tai lieu da co khong bi ghi de mac dinh.
3. **Parse noi dung:** `DocumentParser` tao Reference Document tu HTML hoac PDF. PDF duoc tach text theo trang va doc geometry bang PyMuPDF de tim anh/net ve; HTML duoc chuyen thanh text va link tham chieu. Cac pipeline cau hoi hien chi xu ly PDF, khong chuyen DOC/DOCX/XLS/XLSX thanh cau hoi.
4. **Trich xuat cau hoi:** `QuestionExtractor` nhan dang heading, stem, options A-D, dap an neu co trong tai lieu, dang single/multiple choice, true-false, numeric/short answer, drag-drop va nhom passage. Moi cau giu document ID, URL nguon, so trang, warning va trang thai review. Day la trich xuat theo mau van ban, khong phai OCR hay xac minh dap an.
5. **Xu ly hinh va chuan hoa:** `QuestionVisualProcessor` cat crop bang/chon/do thi theo vi tri cau hoi va gan tham chieu asset; trang scan duoc dua vao OCR/review queue. `QuestionNormalizer` tao stable `question_id`, chuyen record sang schema Question Bank va de trong cac mapping/difficulty chua co bang chung.
6. **Kiem tra va phat hanh:** cac validator kiem tra tai lieu, schema cau hoi va taxonomy. Lifecycle CLI tao mapping pending, tim duplicate candidate, audit cac gate; publisher chi tao snapshot immutable khi taxonomy, cau hoi, mapping, human review va duplicate review deu dat.

Du lieu duoc tach theo muc dich: `data/raw` la nguon PDF, `data/processed` la Reference Document/question bank/crop PNG, con `output` la metadata crawl va validation report. Local mode khong can truy cap mang; online mode tao request ra cac website va phai tuan thu robots/quyen su dung.

## 2. Cau truc thu muc

```text
competency_assessment_and_analysis/
|-- README.md
|-- requirements.txt
`-- crawler/
	|-- main.py
	|-- test_downloader.py
	|-- test_extracted_questions.py
	|-- test_hsa_pipeline.py
	|-- test_parser.py
	|-- test_question_extractor.py
	|-- test_tsa_pipeline.py
	|-- test_question_normalizer.py
	|-- test_question_validator.py
	|-- test_question_visuals.py
	|-- test_question_lifecycle.py
	|-- test_taxonomy.py
	|-- common/
	|   |-- document_downloader.py
	|   |-- document_parser.py
	|   |-- document_utils.py
	|   |-- http_client.py
	|   |-- duplicate_detector.py
	|   |-- processed_storage.py
	|   |-- question_bank_publisher.py
	|   |-- question_extractor.py
	|   |-- question_mapper.py
	|   |-- question_normalizer.py
	|   |-- question_schema.py
	|   |-- question_visuals.py
	|   |-- review_audit.py
	|   |-- robots.py
	|   `-- storage.py
	|-- data/
	|   |-- raw/
	|   |   |-- tsa/                  # PDF TSA dau vao, can giu
	|   |   `-- hsa/                  # PDF HSA duoc phep xu ly
	|   `-- processed/
	|       |-- tsa/                  # JSON TSA, Question Bank va visual crops
	|       `-- hsa/                  # JSON HSA, Question Bank va visual crops
	|-- output/                       # Metadata crawl va report
	|-- pipelines/
	|   |-- hsa_pipeline.py
	|   |-- question_bank_lifecycle.py
	|   `-- tsa_pipeline.py
	|-- taxonomy/
	|   |-- cognitive_levels.json
	|   |-- competency_framework.json
	|   `-- *_draft.md
	|-- sources/
	|   |-- hust_tsa.py
	|   `-- vnu_hsa.py
	`-- validation/
	    |-- hsa_validator.py
	    |-- question_validator.py
	    |-- taxonomy_validator.py
	    `-- tsa_validator.py
```

`crawler/` va `crawler/pipelines/` khong can `__init__.py`: Python 3 ho tro namespace package, nen cac lenh `python -m crawler...` van chay duoc tu thu muc goc. Cac thu muc con khac co `__init__.py` chuan.

## 3. Vai tro cac module

### `crawler/common/`

- `http_client.py`: GET request bang `requests`; cau hinh User-Agent, timeout, retry, rate limit va `truststore` de Python dung kho chung chi Windows.
- `robots.py`: doc `robots.txt` va kiem tra URL co duoc phep crawl hay khong.
- `document_downloader.py`: tai PDF/DOC/DOCX/XLS/XLSX; nhan dang loai file theo Content-Type va extension; luu atomic, khong ghi de file cu mac dinh; tra metadata gom URL, local path, kich thuoc, SHA-256 va timestamp.
- `document_parser.py`: parse HTML/PDF, lay toa do heading/anh/net vector va render crop PDF theo toa do; asset duoc luu ngoai JSON. Parser file local va TSA/HSA question pipelines hien chi xu ly PDF; DOC/DOCX/XLS/XLSX co the duoc downloader luu nhung chua duoc question pipeline parse.
- `document_utils.py`: ham dung chung de tinh SHA-256, lay extension URL va tao UTC timestamp.
- `storage.py`: doc/ghi JSON metadata trong `crawler/output`.
- `processed_storage.py`: doc/ghi JSON processed trong thu muc duoc cau hinh (mac dinh `crawler/data/processed/tsa`); ghi atomic va chi chap nhan ten file JSON nam truc tiep trong thu muc storage.
- `question_extractor.py`: tach cac dang cau hoi, lua chon inline, multi-answer va passage group; giu provenance va gan extraction/human-review status. Module khong gan competency hay difficulty.
- `question_schema.py`: dataclass schema cho normalized question, options, metadata, provenance va quality.
- `question_normalizer.py`: chuyen extracted record sang schema; tao stable ID tu document ID va so cau, chuan hoa options/answer, giu exam, source pages va visual asset references.
- `question_visuals.py`: phan vung theo heading cau hoi, crop anh/do thi/bang va gan asset; chi dua cau co visual evidence vao review queue, danh dau trang scan can OCR.
- `question_mapper.py`: validate mapping record tach rieng theo ID va quan he Exam/Section/Competency/Domain/Topic/Skill/Cognitive Level; khong tu dong gan mapping.
- `duplicate_detector.py`: tim exact/near duplicate candidates de reviewer xu ly; khong tu dong gop hoac xoa cau.
- `question_bank_publisher.py`: publish bank da duyet thanh snapshot versioned va manifest; khong ghi de version cu.
- `review_audit.py`: kiem tra timestamp review theo ISO-8601 co timezone.

### `crawler/sources/`

- `hust_tsa.py`: crawl toi da 20 trang tu website tuyen sinh HUST, kiem tra robots va luu metadata vao `crawler/output/tsa_documents.json`. Crawler hien chu yeu thu thap trang HTML va link file co extension nhan dang duoc.
- `vnu_hsa.py`: crawl muc de tham khao HSA tren `idt.vnu.edu.vn`; phat hien PDF trong ca the lien ket HTML va payload Next.js `documents[].uri`, chi luu metadata URL. Khong crawl noi dung can dang nhap/captcha.

### `crawler/pipelines/tsa_pipeline.py`

- Mac dinh doc `crawler/output/tsa_documents.json`, xu ly cac URL va luu summary vao `crawler/output/tsa_processed_documents.json`.
- Neu chay voi `--local`, quet cac PDF truc tiep trong `crawler/data/raw/tsa/`, parse tung file, tinh hash va luu summary vao `crawler/output/tsa_local_processed_documents.json`.
- Ghi extracted records vao `tsa_extracted_questions.json`, normalized question bank vao `tsa_question_bank.json`, va visual crops vao `crawler/data/processed/tsa/visuals/`.
- Moi cau giu `source_pages` va `visual_assets`; cau tham chieu den hinh/bang hoac nam tren trang co graphics duoc danh dau `visual_content_needs_review`.

### `crawler/pipelines/hsa_pipeline.py`

- Doc `crawler/output/hsa_documents.json`, tai va xu ly PDF vao `crawler/data/raw/hsa/` va `crawler/data/processed/hsa/` sau khi nguoi dung xac nhan da co quyen su dung.
- Co che do `--local` de xu ly PDF da dat trong `crawler/data/raw/hsa/`.
- Ghi `hsa_extracted_questions.json`, normalized `hsa_question_bank.json`, question crops trong `visuals/`, va summary rieng cho che do online/local; `crawler/validation/hsa_validator.py` tao report tu summary local.

### `crawler/pipelines/question_bank_lifecycle.py`

CLI tao mapping templates `pending`, quet duplicate candidates, audit schema/taxonomy/review va goi publisher. Template khong co competency tu dong; reviewer phai dien va xac nhan mapping.

### `crawler/validation/`

- `tsa_validator.py`: kiem tra summary, processed JSON, noi dung, PDF, hash va URL/hash trung lap; ghi report JSON. Mac dinh doc summary online `crawler/output/tsa_processed_documents.json`. Khi validate local PDF, truyen summary local `crawler/output/tsa_local_processed_documents.json`.
- `hsa_validator.py` tai su dung kiem tra tai lieu cua TSA, mac dinh doc summary HSA local.
- `question_validator.py` kiem tra cau hoi da chuan hoa: truong bat buoc, loai cau, options/answer, provenance, quality va passage group. Validator khong xac minh dap an dung ve mat hoc thuat.
- `taxonomy_validator.py` kiem tra framework taxonomy TSA/HSA, cognitive levels va cau hinh cau truc HSA 2026.

## 4. Du lieu hien co

Tai thoi diem viet tai lieu:

- `crawler/data/raw/tsa/` co 3 PDF minh hoa TSA cong khai: Toan, Tu duy Doc hieu va Tu duy Khoa hoc/Giai quyet van de.
- `crawler/data/processed/tsa/` co JSON tu PDF local va HTML cu; pipeline cap nhat `tsa_extracted_questions.json` va `tsa_question_bank.json` khi chay.
- `tsa_question_mappings.json` va `hsa_question_mappings.json` da duoc tao tu bank hien co voi cac record rong o trang thai `pending`; chua co taxonomy mapping nao duoc duyet.
- Question Bank da chuan hoa nhung van can human review; page images la bang chung nguon, khong phai OCR hay dien giai tu dong noi dung bieu do. Snapshot hien tai: TSA co 35/39 cau qua structural validator; HSA co 16/225, phan lon record loi do answer key thieu. Khong tu dien answer de lam tang so cau valid.
- Cac cau co layout/table/footnote khong chac chan duoc danh dau `needs_review`; `human_review_status` mac dinh la `pending`.
- `crawler/output/tsa_local_processed_documents.json` la summary cho 3 PDF local; `tsa_local_validation_report.json` la report local.
- `crawler/output/tsa_documents.json`, `tsa_processed_documents.json` va `tsa_validation_report.json` la metadata/report tu luong crawl HTML HUST truoc do.
- `crawler/data/processed/hsa/hsa_2026_framework.json` la framework cau truc HSA, khong phai de thi. Crawler chi luu metadata URL trong `crawler/output/hsa_documents.json`. PDF de tham khao 2025 neu phat hien co dieu khoan yeu cau chap thuan truoc khi sao chep/su dung, nen chua duoc luu hoac extract trong workspace. Khi co quyen, pipeline tao processed document, extracted questions, summary va report rieng.
- `crawler/output/sources.json` duoc `crawler/main.py` tao boi crawler tong quat; no khong phai dau vao cua TSA pipeline.

File trong `data/raw/tsa` la nguon goc quan trong. Cac file processed, summary va report co the tao lai bang pipeline/validator, nhung xoa chung se mat ket qua da luu cho den khi chay lai.

Khong nen xoa rieng mot file processed neu summary tuong ung van con tham chieu toi `processed_path`; validator se danh dau tai lieu thieu processed file. Cac PNG trong `visuals/` cung duoc tham chieu tu truong `visual_assets` trong document/question JSON.

## 5. Cai dat va chay

Chay cac lenh tu thu muc goc du an trong PowerShell.

### Tao va kich hoat moi truong

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

Dependencies duoc khai bao: `beautifulsoup4`, `requests`, `truststore`, `pypdf`, `pymupdf`.

### Crawl metadata HTML HUST

```powershell
python -m crawler.sources.hust_tsa
```

Ket qua: `crawler/output/tsa_documents.json`.

### Xu ly PDF da tai ve/local

Dat PDF vao `crawler/data/raw/tsa/`, sau do chay:

```powershell
python -m crawler.pipelines.tsa_pipeline --local
```

Ket qua chinh:

- Chi tiet moi tai lieu: `crawler/data/processed/tsa/HUST_TSA_LOCAL_*.json`
- Extracted questions: `crawler/data/processed/tsa/tsa_extracted_questions.json`
- Normalized Question Bank: `crawler/data/processed/tsa/tsa_question_bank.json`
- Visual crops: `crawler/data/processed/tsa/visuals/`
- Danh sach processed: `crawler/output/tsa_local_processed_documents.json`

### Crawl va xu ly de tham khao HSA cong khai

```powershell
python -m crawler.sources.vnu_hsa
python -m crawler.pipelines.hsa_pipeline --rights-cleared
```

Ket qua chinh:

- Metadata tai lieu cong khai: `crawler/output/hsa_documents.json`
- PDF tai ve sau khi xac nhan quyen: `crawler/data/raw/hsa/`
- Question Bank: `crawler/data/processed/hsa/hsa_question_bank.json`
- Anh trang va chi tiet tai lieu: `crawler/data/processed/hsa/`
- Summary: `crawler/output/hsa_processed_documents.json`

PDF tai lieu tham khao co thong bao ban quyen can duoc chap thuan truoc khi sao chep/su dung. Lenh online mac dinh tu choi tai file; chi them `--rights-cleared` neu da co quyen. De xu ly PDF duoc phep da co san, chay `python -m crawler.pipelines.hsa_pipeline --local`. Summary local la `crawler/output/hsa_local_processed_documents.json`; report validation tao bang `python -m crawler.validation.hsa_validator`.

Question Bank luu `source_pages` va `visual_assets` tro toi crop PNG theo cau trong `visuals/`; asset co `bbox` va page number de truy vet vi tri tren PDF. Trang co it hon 30 ky tu trich xuat va co anh/do hoa duoc ghi vao `pages_needing_ocr`. OCR/nhan dang noi dung do thi va cau hoi trong trang scan chua tu dong; cac muc nay phai duoc OCR va human-review truoc khi danh dau hoan tat.

### Validate PDF local

```powershell
python -c "from crawler.validation.tsa_validator import TsaValidator; TsaValidator(input_file='crawler/output/tsa_local_processed_documents.json', report_file='crawler/output/tsa_local_validation_report.json').run()"
```

### Chay pipeline tu metadata crawl

```powershell
python -m crawler.pipelines.tsa_pipeline
```

Che do nay dung metadata URL trong `tsa_documents.json`; khong thay the cho local PDF pipeline.

### Chay tests

```powershell
python -m unittest discover -s crawler -p "test_*.py" -v
```

### Validate question bank va taxonomy

Question bank sau khi normalize co the duoc kiem tra cau truc bang `QuestionValidator`:

```powershell
python -c "import json; from crawler.validation.question_validator import QuestionValidator; questions=json.load(open('crawler/data/processed/tsa/tsa_question_bank.json', encoding='utf-8')); result=QuestionValidator().validate(questions); print(result); raise SystemExit(not result['valid'])"
```

Kiem tra taxonomy va cau hinh HSA 2026:

```powershell
python -c "from crawler.validation.taxonomy_validator import TaxonomyValidator; result=TaxonomyValidator().validate(); print(result); raise SystemExit(not result['valid'])"
```

`QuestionValidator` co the bao loi voi record chua du thong tin (vi du dap an/options objective bi thieu); warning chat luong trong cau hoi khong tu dong dong nghia record khong hop le. Voi cau `true_false` khong trich xuat duoc options, cac dap an chu `Đúng`, `Sai`, `True`, `False` duoc chap nhan; gia tri khac van la loi. Lenh tren tra exit code 1 neu Question Bank khong hop le; can doc danh sach `errors` de xu ly tung record. Du lieu TSA hien tai co mot so record can tiep tuc sua/review, nen validator co the khong tra ve hop le.

### Mapping, duplicate review va approved export

Workflow mapping duoc tach khoi extracted bank, de goi y khong bi coi la su that:

```powershell
# Tao mapping templates rong, trang thai pending; khong ghi de file da co
python -m crawler.pipelines.question_bank_lifecycle --exam TSA --init-mappings

# Tao duplicate candidate report de reviewer xu ly
python -m crawler.pipelines.question_bank_lifecycle --exam TSA --scan-duplicates

# Audit question schema, taxonomy references, review states va duplicates
python -m crawler.pipelines.question_bank_lifecycle --exam TSA

# Publish chi khi taxonomy/question/mapping/duplicate gates deu dat
python -m crawler.pipelines.question_bank_lifecycle --exam TSA --publish --publisher "reviewer-id"
```

Doi `TSA` thanh `HSA` de chay cho bank HSA. Mapping templates nam tai `crawler/data/processed/<exam>/<exam>_question_mappings.json`; duplicate candidates nam tai `.../<exam>_duplicate_candidates.json`. Review duplicate duoc luu trong `<exam>_duplicate_reviews.json`, vi du:

Trong workspace hien tai, mapping templates TSA (39 cau) va HSA (225 cau) da duoc tao. `--init-mappings` chi tao file neu chua ton tai va se tu choi ghi de; reviewer co the sua cac record pending trong sidecar mapping.

```json
[
  {
    "question_id_a": "DOC_Q001",
    "question_id_b": "DOC_Q002",
    "decision": "not_duplicate",
    "reviewer": "reviewer-id",
    "reviewed_at": "2026-09-27T12:00:00+00:00"
  }
]
```

Mapping record co ca `cognitive_level_id` va `proficiency_level_id`; chi dung mot trong hai khi ap dung. HSA English dung proficiency IDs tu `proficiency_distribution` trong HSA config, khong gan M1-M3. Mapping `approved` bat buoc co `mapping_source: human`, `reviewer` va `reviewed_at`; `rule`/`ai_suggestion` chi la candidate. Human review cho cau cung can status `approved`, reviewer va timestamp ISO-8601 co timezone. `needs_revision` khong duoc publish. Near duplicate can duoc reviewer danh dau `not_duplicate` hoac `duplicate`; exact duplicate va duplicate da xac nhan se chan publish.

Publisher tao `crawler/data/processed/<exam>/approved/<exam>_question_bank_approved_v0001.json` va publication manifest cung version. Moi cau phai co `metadata.difficulty` gom `planned` va `empirical`; empirical null cho den khi co response data. Snapshot cu khong bi ghi de. `competency_framework.json` hien con `status: draft`, Question Bank TSA con record can sua/review va chua co mapping da duyet; vi vay publish hien tai se bi tu choi. Chi doi framework sang `approved` sau khi co sign-off taxonomic thuc te, khong doi de vuot gate.

## 6. Dinh dang du lieu

### Reference Document

`DocumentParser` tao Reference Document cho HTML va PDF. Cac truong chung gom `source`, `source_url`, `document_type`, `title`, `content_type`, `content`, `url` va `text`.

- HTML: co them danh sach `links`; noi dung la text sau khi loai `script`, `style`, `noscript`.
- PDF: co `page_count` va `pages`; moi page co so trang 1-based va text trich xuat. PDF bytes duoc parse bang `pypdf`.
- Document xu ly boi pipeline co the co them `document_id`, `exam`, `local_path`, `sha256`, `downloaded_at`, `visual_assets`, `visual_page_numbers` va `pages_needing_ocr`.

### Extracted Question

Moi extracted record giu cac truong `question_number`, `stem`, `options`, `question_type`, `answer`, provenance (`document_id`, `source_url`, `source`, `page_number`), `quality`, va metadata passage (`group_id`, `group_type`, `passage`) neu thuoc nhom. Multi-answer duoc luu thanh list. `question_number` van la so thu tu trong tai lieu, chua phai khoa duy nhat; `question_id` se duoc tao o buoc normalization.

Extractor nhan dien heading cau hoi tieng Viet co dau/khong dau va tieng Anh; options A-D tach dong/cung dong; dap an don/nhieu; true-false; numeric/short answer; drag-drop duoc gan `response_mode`; va passage group. `source_pages` luu tat ca trang co noi dung cua cau. `explanation` la `null` neu nguon khong cung cap.

`quality.extraction_status` la `extracted` hoac `needs_review`; `human_review_status` mac dinh la `pending`. Sau review, `reviewer` va `reviewed_at` duoc luu cung status `approved`, `rejected` hoac `needs_revision`. Cac warning co the gap gom `missing_answer`, `choice_options_incomplete`, `unexpected_option_order`, `passage_boundary_needs_review`, `layout_or_table_content_needs_review` va `visual_content_needs_review`.

### Normalized Question

`QuestionNormalizer` tao schema on dinh, voi cac truong chinh:

- `question_id`: ID theo mau `<document_id>_Q<number padded to 3 digits>`.
- `question_number`, `stem`, `question_type`, `options`, `correct_answer`, `explanation`.
- `metadata`: exam, section, competency, domain, topic, skill, cognitive_level, proficiency_level, difficulty.
- `provenance`: document_id, source, source_url, page_number.
- `quality`, `passage`, `group_id`, `group_type`, `source_pages`, `visual_assets`, `extra`.

Options duoc serialize thanh list `{key, text}`; dap an thanh list chuoi hoac `null`. `metadata.difficulty` co dang `{"planned": ..., "empirical": ...}`; mac dinh ca hai la `null`. Normalizer khong tu gan competency/cognitive level/proficiency hay empirical difficulty. Validator gioi han empirical trong khoang 0..1; publisher yeu cau co ca hai field nhung chap nhan gia tri null neu chua co bang chung. HSA English dung `proficiency_level` rieng thay vi M1-M3.

### Visual Assets

Moi asset luu `asset_id`, `kind`, `page_number`, `bbox` va `path` tuong doi voi thu muc processed. `kind` co the la `question_visual_crop` hoac `ocr_review_crop`. Anh PNG nam trong `visuals/`; du lieu document va question chi luu tham chieu, khong nhung binary anh vao JSON.

## 7. Logic cat anh va review

`QuestionVisualProcessor` doc geometry PDF bang PyMuPDF va:

1. Dung heading cau hoi tren trang de tao vung noi dung theo trang.
2. Tim anh raster giao voi vung cau hoi va cac net vector; cue nhu “nhu hinh”, “do thi”, “bieu do”, `graph`, `chart` cho phep xu ly ca do thi vector chi gom it net.
3. Gop cac crop gan/chong lap, gioi han bbox trong trang, render PNG va gan asset vao cau/document.
4. Danh dau cau co asset la `needs_review`; trang it text nhung co anh/do hoa duoc ghi vao `pages_needing_ocr` va co the co crop `ocr_review_crop`.

Day la phat hien/cat theo hinh hoc, khong phai nhan dang noi dung anh. Crop la bang chung de kiem tra thu cong; chu trong anh scan va noi dung y nghia cua do thi chua duoc OCR tu dong.

## 8. Taxonomy va cau truc de thi

- `crawler/taxonomy/competency_framework.json`: framework noi bo dang draft cho TSA va HSA, phan cap exam/section/competency/domain/topic.
- `crawler/taxonomy/cognitive_levels.json`: cac muc M1-M3; day la danh muc tham chieu, khong duoc extractor tu dong gan vao cau.
- `crawler/data/processed/hsa/hsa_2026_framework.json`: cau hinh cau truc bai thi HSA 2026, khong phai de thi hay question bank.
- Cac file `*_draft.md`: ghi chu phat trien taxonomy; can doi chieu truoc khi xem la chuan chinh thuc.

## 9. Crawler tong quat cu

`crawler/main.py` la crawler tong quat cu, crawl cac trang bat dau HUST va VNU (toi da 20 trang moi nguon), loc domain, kiem tra robots.txt va ghi `crawler/output/sources.json`. File nay khong phai dau vao cua TSA/HSA pipelines. Luong TSA chuyen dung nam trong `crawler.sources.hust_tsa`; luong HSA chuyen dung nam trong `crawler.sources.vnu_hsa`.

## 10. File dau ra va ghi de

### TSA

- `HUST_TSA_000001.json`...: snapshot Reference Document tu pipeline online; co the la HTML trang tuyen sinh hoac PDF tai ve.
- `HUST_TSA_LOCAL_000001.json`...: Reference Document tao tu PDF local.
- `tsa_extracted_questions.json`: cau hoi raw tu lan chay TSA gan nhat.
- `tsa_question_bank.json`: cung bo cau hoi da chuan hoa tu lan chay TSA gan nhat.
- `tsa_processed_documents.json` va `tsa_local_processed_documents.json`: summary rieng cho online va local.
- `visuals/`: crop PNG duoc tao tu PDF; khong phai file OCR.

### HSA

- `VNU_HSA_*.json` va `VNU_HSA_LOCAL_*.json`: Reference Document online/local.
- `hsa_extracted_questions.json`, `hsa_question_bank.json`: extracted records va Question Bank tu lan chay HSA gan nhat.
- `hsa_processed_documents.json` va `hsa_local_processed_documents.json`: summary online/local; report local mac dinh la `hsa_local_validation_report.json`.
- `visuals/`: crop PNG tu PDF HSA da xu ly.

`HUST_TSA_*.json` online khong dong nghia la de thi TSA; nhieu file la snapshot trang HTML thong tin tuyen sinh. Cac summary `*_processed_documents.json` giu `processed_path` tro den cac file chi tiet nay. Neu xoa snapshot, can cap nhat summary tuong ung hoac validation se bao `processed_file_exists = false`.

Chay pipeline online va local cung ghi vao extracted/question-bank JSON cung exam. Vi vay ket qua cua lan chay gan nhat se thay bo cau hoi cung exam truoc do. Neu thu muc raw khong co PDF, pipeline local TSA/HSA dung va khong ghi de summary, extracted questions hoac Question Bank cu; hay kiem tra `crawler/data/raw/<exam>/` truoc khi chay neu muon tao ket qua moi.

## 11. Gioi han va dieu kien su dung

- Chay crawler/pipeline online se tao request ra website. HTTP client co timeout, retry va delay; crawler chuyen dung kiem tra robots.txt khi thu thap metadata.
- HSA crawler chi tim tai lieu PDF cong khai va luu URL metadata. HSA pipeline online mac dinh bi chan; chi truyen `--rights-cleared` sau khi da xac nhan quyen tai/xu ly. Che do `--local` khong tai file va khong can flag nay.
- He thong khong OCR. PDF scan chi duoc danh dau trong `pages_needing_ocr`; noi dung chu trong hinh/scan can xu ly bang cong cu OCR khac va human review.
- Extractor la heuristic theo text va bo cuc; options, bang true/false, cong thuc, footnote, heading khong dung mau hoac noi dung qua trang co the bi tach sai.
- He thong khong tu tao explanation, khong xac minh dap an ve mat hoc thuat, khong gan competency/cognitive level/difficulty va khong danh gia do chinh xac cua noi dung crawl.
- De thi va tai lieu tham khao co the duoc bao ho ban quyen. Chi tai, luu tru, xu ly va chia se theo dieu kien/quyen tu don vi phat hanh; khong cong bo lai Question Bank neu chua duoc phep.

## 12. Lo trinh Question Bank chinh thuc

Question Bank chinh thuc la mot deliverable co quan tri va audit, khong phai chi la JSON do extractor tao. Luong muc tieu:

```text
Source Documents
		-> Extracted Questions
		-> Normalized Questions
		-> Taxonomy Mapping
		-> Quality and Duplicate Review
		-> Metadata Completion
		-> Structural Validation
		-> Human Review and Approval
		-> Immutable Question Version
		-> Approved Question Bank
```

Question Bank sau nay se la nguon cho Test Builder, practice, AI Assistant, scoring va competency analytics. Cac giai doan duoi day la roadmap; khong dong nghia cac tinh nang chua danh dau da duoc implement.

### Trang thai hien tai

- Da co extracted Question va normalized Question voi `question_id` on dinh, provenance, quality, passage group, source pages va tham chieu visual assets.
- Da co `competency_framework.json`, `cognitive_levels.json`, cau hinh HSA 2026 va `taxonomy_validator.py`.
- `skill_taxonomy_draft.md` co candidate mapping cho 39 cau TSA; cac mapping trong file van la de xuat can human review, khong phai metadata da duyet trong Question Bank.
- `hsa_2026_competency_draft.md` va `hsa_2026_skill_taxonomy_draft.md` la tai lieu draft. HSA question bank hien tai khong co nghia taxonomy HSA da duoc duyet.
- Da co mapping validator theo taxonomy, exact/near duplicate detector va publisher tao snapshot approved versioned. Chua co mapping nao duoc human approve va chua tao bank approved tu du lieu hien tai.
- `QuestionValidator` kiem tra cau truc va review audit fields; publisher yeu cau quality/mapping approved boi human va duplicate review da xu ly.
- Cau `true_false` co answer dang chu chi hop le khi options khong duoc trich xuat va answer la mot trong `Đúng`, `Sai`, `True`, `False`.
- Schema normalized hien tai nhung metadata taxonomy truc tiep vao `metadata`; chua co bang/entity rieng va question-to-competency mapping record cho database.

### Giai doan 1: Chot taxonomy TSA va HSA

Muc tieu la tao framework noi bo co the phuc vu Question Bank, Test Builder, scoring, competency profile, learning analytics, recommendation, dashboard va AI question generation. Taxonomy khong chi la nhan metadata cho cau hoi.

Mo hinh phan loai muc tieu:

```text
Exam
	-> Section
		-> Competency
			-> Domain
				-> Topic
					-> Skill
	-> Question references Cognitive Level
```

`Difficulty` la metadata doc lap, khong phai mot bac taxonomy. Can phan biet Exam/Section, nang luc duoc do (Competency), mien kien thuc (Domain), chu de (Topic), hanh dong co the quan sat (Skill), cognitive level va difficulty.

Entity taxonomy nen co ID on dinh va duoc quan ly tach khoi Question Bank. Trong database, Question mapping se tham chieu den cac entity nay thay vi chi luu ten tu do. JSON framework hien tai dang nested; mo hinh mapping record va relational/persistent store la cong viec tiep theo, chua duoc trien khai.

Truoc khi tao mapping, can review TSA/HSA section va competency, domain/topic coverage, skill definitions, quy tac primary path va cac tag phu. Co the duy tri taxonomy dung configuration JSON; khong hard-code danh muc trong Python.

### Giai doan 2: Chot Cognitive Level

M1-M3 la thang noi bo de annotation, khong tuyen bo la thang chinh thuc cua TSA/HSA neu tai lieu nguon khong quy dinh nhu vay. Rubric de review:

- `M1`: nhan biet, tinh truc tiep hoac ap dung quy trinh/cong thuc quen thuoc.
- `M2`: ket hop du kien, phan tich quan he, van dung hoac suy luan muc trung gian.
- `M3`: nhieu buoc suy luan, tong hop nhieu kien thuc hoac giai quyet tinh huong moi.

Chi gan cognitive level khi co tieu chi va bang chung. Neu khong du thong tin, de `null`; khong suy dien tu nhan M1/M2/M3 OCR duoc neu chua doi chieu tai lieu goc. HSA English can thang proficiency rieng neu framework HSA yeu cau; khong ep dong nhat proficiency voi M1-M3.

### Giai doan 3: Pilot Question Mapping

Khong map hang loat ngay. Chon khoang 10 cau dai dien tu cac section va dang cau khac nhau: single/multiple choice, passage, true-false, short/numeric answer va cau co hinh/bang; bao phu cac muc M1-M3 neu co can cu. Mapping pilot dung de phat hien taxonomy thieu node, chong lap hoac khong phan biet duoc muc tieu do.

Mapping record du kien:

```json
{
  "question_id": "HUST_TSA_LOCAL_000001_Q001",
  "exam": "TSA",
  "section_id": "TSA_MATH",
  "competency_id": "TSA_MATH_QUANT",
  "domain_id": null,
  "topic_id": null,
  "skill_id": null,
  "cognitive_level_id": null,
  "proficiency_level_id": null,
  "mapping_source": "human",
  "mapping_status": "pending",
  "reviewer": null,
  "reviewed_at": null
}
```

Gia tri `mapping_source` muc tieu gom `human`, `rule`, `ai_suggestion`; `mapping_status` gom `pending`, `approved`, `rejected`. Chi `human`/review duyet moi duoc publish. Mapping tu AI hoac rule la goi y, khong phai su that. Trong pilot, cac truong khong chac chan phai de `null` thay vi doan.

Sau khi pilot duoc review va taxonomy duoc chot, moi map toan bo 39 cau TSA hien co. Cac candidate mapping trong `skill_taxonomy_draft.md` la tai lieu tham khao de review, khong tu dong import thanh mapping approved.

### Giai doan 4: Difficulty va metadata

Phan biet hai loai do kho:

- `planned`: do kho du kien truoc khi dua vao su dung, theo rubric noi bo va co the do reviewer gan.
- `empirical`: do kho tinh tu ket qua nguoi hoc thuc te, ban dau la `null`. Vi du Facility Index co the tinh bang so nguoi tra loi dung chia so nguoi lam cau, sau khi co du lieu va quy tac xu ly bo trong.

Khong ghi cam nhan cua nguoi viet cau vao `empirical`. Normalized schema da tach `planned` va `empirical`, nhung rubric noi bo va cach gan planned difficulty van can reviewer thong qua truoc khi ap dung.

### Giai doan 5: Quality Review va Human Review

Moi cau can duoc review theo cac nhom:

- Structural: stem, question type, options, answer va group structure.
- Content: cau hoi hoan chinh, dap an nhat quan, loi OCR va loi tach cau.
- Visual: crop dung cau, hinh/bang/do thi day du, truc va legend khong bi cat; xac dinh co can OCR.
- Metadata: exam, section, competency, domain, topic, skill, cognitive level va difficulty.
- Provenance: document, URL, trang nguon va visual asset.

`extraction_status = extracted` chi noi extraction khong phat hien warning; khong co nghia cau da duoc chap thuan. Human review la checkpoint bat buoc truoc publish. Workflow ho tro `pending`, `approved`, `rejected`, `needs_revision`; moi quyet dinh khac pending can reviewer va timestamp. `needs_revision` khong the duoc publish.

### Giai doan 6: OCR va review visual

Trang scan hien duoc dua vao `pages_needing_ocr`, nhung OCR chua duoc tich hop. Luong de xuat: PDF scan -> OCR -> question extraction -> doi chieu voi crop/trang goc -> human review. OCR output khong duoc xem la ground truth. Cac cau co `visual_assets` can review crop truoc khi approve; neu hinh bi cat hoac OCR chua chac, chuyen `needs_revision`.

### Giai doan 7: Duplicate detection va validation cuoi

Duplicate detection nen tach ba muc:

1. Exact duplicate: hash noi dung da chuan hoa.
2. Near duplicate: similarity tren stem/options sau normalize, nguong can duoc review va hieu chinh.
3. Semantic duplicate: embedding/search ngu nghia; de danh dau goi y cho reviewer, khong tu dong xoa.

Sau mapping va review, `QuestionValidator` kiem tra schema/answer/provenance/review audit; `QuestionMapper` kiem tra taxonomy IDs va parent relationship; `DuplicateDetector` tao exact/near candidates va publication gate yeu cau xu ly. Semantic duplicate detection bang embedding chua duoc implement.

### Giai doan 8: Approved bank va versioning

Publisher hien tao snapshot versioned `*_question_bank_approved_vNNNN.json` va manifest cho cac cau da approved. Trang thai `Draft -> Review -> Published -> Retired` chua duoc luu thanh lifecycle store rieng; `*_question_bank.json` van la output normalized, khong phai bank approved.

Moi lan publish tao mot snapshot va manifest version moi; publisher khong ghi de snapshot cu. Lich su thay doi tung cau, ly do sua, retire/rollback va lien ket version voi attempt chua co database/audit event store.

### Checkpoint truoc Assessment Engine

Chi chuyen sang Test Builder/Assessment Engine khi dat du:

- TSA va HSA taxonomy duoc chot, co ID on dinh, duoc validator kiem tra va co review gate.
- Question extraction/normalization co stable ID, question type, answer, provenance va source pages.
- Pilot mapping da duoc human review; mapping con lai co status va khong tu dong coi goi y AI/rule la approved.
- Competency, section, domain, topic, skill va cognitive level duoc validate; du lieu chua chac chan la `null`.
- Planned va empirical difficulty tach rieng; empirical chi co sau khi co response data.
- Structural/content/visual review, OCR queue va duplicate detection co ket qua.
- Approved export chi chua version da duyet; version published bat bien va co audit trail.

### Cac module/file tiep theo du kien

- Da co: `question_mapper.py`, `duplicate_detector.py`, `question_bank_publisher.py`, lifecycle CLI va validators/schema co ban.
- Can tiep tuc review: `competency_framework.json`, `cognitive_levels.json` va taxonomy draft TSA/HSA. Framework dang `draft`; JSON hien chua khai bao Skill nodes.
- Co the tach configuration thanh `tsa_taxonomy.json` va `hsa_taxonomy.json` neu sau review thay can thiet; hien tai framework dang gom TSA/HSA chung trong `competency_framework.json`.
- Chua co: mapping da human review, semantic duplicate detection, OCR, per-question audit event store va attempt-linked immutable versions.
- Hien chua co bank approved; chi publish sau khi framework duoc sign-off va cac cau/mapping da qua cac gate. Khong tao bank approved mau tu cac mapping draft.
