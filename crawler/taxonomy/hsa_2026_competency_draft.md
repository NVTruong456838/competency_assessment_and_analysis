# HSA 2026 Competency Framework Draft

## Scope

- Status: `draft`
- Taxonomy owner: Smart Assessment AI internal framework
- Exam: HSA 2026
- Structure source: https://www.hsa.edu.vn/huong-dan/dang-thuc-bai-thi
- Purpose: define primary measurement paths for annotation and analytics; it
  does not claim to be an official taxonomy of ĐHQGHN.

The official HSA structure identifies broad core competencies and subject
areas. This draft selects a small set of **annotatable primary competencies**
for the system. A question receives one primary path; additional subject,
stimulus, or format characteristics can be stored as secondary tags later.

```text
HSA
├── HSA_MATH_DATA
├── HSA_LITERATURE_LANGUAGE
└── HSA_OPTIONAL
    ├── HSA_SCIENCE
    └── HSA_ENGLISH
```

`HSA_OPTIONAL` is a section container, not a question-bearing section. Every
Part 3 question must use either `HSA_SCIENCE` or `HSA_ENGLISH` as its section.

## HSA_MATH_DATA — Toán học và xử lý số liệu

| ID | Competency | Internal operational definition | Candidate domain direction |
| --- | --- | --- | --- |
| `HSA_MATH_LOGIC_QUANT` | Lập luận và tư duy định lượng | Phân tích quan hệ số lượng, cấu trúc toán học và suy luận logic để kết luận. | Số học, đại số, hàm số, hình học, giải tích. |
| `HSA_MATH_MODEL` | Mô hình hóa toán học | Biểu diễn bối cảnh bằng biến, quan hệ, giả định và diễn giải kết quả. | Mô hình thực tiễn, đại lượng, tối ưu hóa. |
| `HSA_MATH_PROBLEM` | Giải quyết vấn đề toán học | Lựa chọn chiến lược và phối hợp nhiều bước để giải bài toán có ràng buộc. | Bài toán nhiều bước, điều kiện, chiến lược giải. |
| `HSA_MATH_DATA_ANALYSIS` | Phân tích dữ liệu và xác suất thống kê | Đọc, tính toán và diễn giải bảng, biểu đồ, xác suất và chỉ số thống kê. | Thống kê mô tả, xác suất, bảng/biểu đồ. |

## HSA_LITERATURE_LANGUAGE — Văn học - Ngôn ngữ

| ID | Competency | Internal operational definition | Candidate domain direction |
| --- | --- | --- | --- |
| `HSA_LANG_LANGUAGE_USE` | Sử dụng tiếng Việt | Vận dụng từ vựng, ngữ pháp, ngữ nghĩa và quy tắc diễn đạt phù hợp ngữ cảnh. | Từ vựng, ngữ pháp, ngữ nghĩa, hành văn. |
| `HSA_LANG_READING` | Đọc hiểu văn bản | Xác định ý chính, chi tiết, mục đích và thông tin trong văn bản. | Văn bản thông tin, văn bản văn học, thông tin trực tiếp. |
| `HSA_LANG_TEXT_ANALYSIS` | Phân tích văn bản và văn học | Phân tích cấu trúc, hình tượng, nội dung tư tưởng và cách biểu đạt. | Cấu trúc, lập luận, thể loại, nghệ thuật biểu đạt. |
| `HSA_LANG_EVALUATION` | Đánh giá và diễn giải | Đánh giá, so sánh và diễn giải quan điểm, giá trị hoặc hiệu quả biểu đạt. | Lập luận, quan điểm, giá trị tác phẩm, diễn giải. |

## HSA_SCIENCE — Khoa học (Part 3, chọn 3 trong 5 môn)

| ID | Competency | Internal operational definition | Candidate domain direction |
| --- | --- | --- | --- |
| `HSA_SCI_CONCEPT` | Vận dụng kiến thức khoa học | Vận dụng kiến thức cốt lõi của môn được chọn để giải thích hoặc tính toán. | Vật lí, Hóa học, Sinh học, Lịch sử, Địa lí. |
| `HSA_SCI_DATA` | Phân tích dữ liệu và tư liệu | Đọc, so sánh và diễn giải bảng, biểu đồ, thí nghiệm, bản đồ hoặc tư liệu. | Dữ liệu thực nghiệm, biểu đồ, bảng, bản đồ, tư liệu lịch sử. |
| `HSA_SCI_REASON` | Lập luận dựa trên bằng chứng | Dùng dữ kiện và bằng chứng để giải thích quan hệ, đánh giá giả thuyết hoặc kết luận. | Bằng chứng, nguyên nhân - hệ quả, giả thuyết, lập luận lịch sử/xã hội. |
| `HSA_SCI_PROBLEM` | Giải quyết vấn đề khoa học/xã hội | Xác định vấn đề, tổ chức thông tin và chọn lời giải có căn cứ. | Tình huống khoa học, môi trường, xã hội, thực hành/thí nghiệm. |

Each `HSA_SCIENCE` question must carry one additional `subject_choice` value:
`PHYSICS`, `CHEMISTRY`, `BIOLOGY`, `HISTORY`, or `GEOGRAPHY`.

## HSA_ENGLISH — Tiếng Anh (Part 3)

| ID | Competency | Internal operational definition | Candidate domain direction |
| --- | --- | --- | --- |
| `HSA_ENG_LANGUAGE_USE` | Sử dụng ngôn ngữ | Vận dụng từ vựng, ngữ pháp và diễn đạt viết trong ngữ cảnh. | Vocabulary, grammar, written expression. |
| `HSA_ENG_READING` | Đọc hiểu tiếng Anh | Hiểu ý chính, chi tiết, cấu trúc, thái độ và nghĩa từ/cụm từ trong văn bản. | Reading comprehension, text structure, author purpose. |
| `HSA_ENG_SITUATIONAL_REASONING` | Suy luận và giải quyết tình huống bằng tiếng Anh | Suy luận và lựa chọn cách dùng ngôn ngữ phù hợp theo tình huống. | Pragmatics, inference, situational problem solving. |

## Mapping and modelling rules

1. `HSA_MATH_DATA` and `HSA_LITERATURE_LANGUAGE` are mandatory sections.
2. `HSA_SCIENCE` and `HSA_ENGLISH` are mutually exclusive Part 3 branches.
3. A Science question must include `subject_choice`; an English question must
   use paper code `Q21`.
4. Science paper codes are represented as `Q01-Q10` until official documents
   for each code-to-subject combination are catalogued.
5. Question groups are entities with shared context. A member question retains
   its own `question_type`, `group_id`, and primary competency path.
6. No candidate mapping becomes `approved` or `published` without human review.

## Before JSON implementation

The next review must confirm:

1. the 15 primary competencies above;
2. the Domain/Topic depth for each HSA subject;
3. whether language/text-type data is a primary taxonomy node or a secondary tag;
4. cognitive-level criteria for HSA Science and a separate proficiency scale for
   HSA English;
5. public source documents permitted for HSA question acquisition.
