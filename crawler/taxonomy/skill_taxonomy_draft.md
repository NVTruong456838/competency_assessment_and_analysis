# TSA Skill Taxonomy Draft

## Status and scope

- Status: `draft`
- Taxonomy owner: Smart Assessment AI internal framework
- Scope: TSA only; HSA is intentionally excluded.
- Evidence reviewed: 39 extracted TSA questions in
  `crawler/data/processed/tsa/tsa_question_bank.json`.
- Mapping status: every mapping in this document is a candidate and requires
  human review before it can become question metadata.

The taxonomy uses one **primary measurement path** for each question:

```text
Section -> Competency -> Domain -> Topic -> Skill -> Cognitive Level -> Question
```

Text type, stimulus format, and other secondary characteristics may be added
later as tags. They must not replace the primary measurement path used for
competency scoring.

## Proposed skill ID convention

```text
SKILL_<SECTION>_<TOPIC>_<ACTION>
```

Examples: `SKILL_MATH_EQUATION_SOLVE`, `SKILL_READ_MAIN_IDEA_IDENTIFY`, and
`SKILL_SCI_CHART_INTERPRET`.

## Tư duy Toán học

| Competency | Domain | Topic | Proposed skill | Operational description | Example question form |
| --- | --- | --- | --- | --- | --- |
| `TSA_MATH_QUANT` | Number & Arithmetic | Number properties | `SKILL_MATH_NUMBER_REMAINDER` | Tính số dư, tính chia hết và quan hệ giữa các số nguyên. | Số dư của biểu thức hoặc điều kiện chia hết. |
| `TSA_MATH_QUANT` | Number & Arithmetic | Digit properties | `SKILL_MATH_DIGIT_ANALYZE` | Phân tích chữ số, tổng chữ số và biểu diễn thập phân. | Tìm giá trị từ tổng/chữ số của một số. |
| `TSA_MATH_QUANT` | Algebra | Equation | `SKILL_MATH_EQUATION_SOLVE` | Giải phương trình bằng biến đổi tương đương phù hợp. | Phương trình đại số hoặc lượng giác cơ bản. |
| `TSA_MATH_QUANT` | Algebra | Inequality | `SKILL_MATH_INEQUALITY_SOLVE` | Giải bất phương trình và xác định tập nghiệm. | Đếm hoặc so sánh nghiệm thỏa điều kiện. |
| `TSA_MATH_QUANT` | Function | Graph and variation | `SKILL_MATH_FUNCTION_GRAPH_ANALYZE` | Đọc đồ thị, xác định cực trị, giao điểm và khoảng biến thiên. | Điền thông tin từ đồ thị hàm số. |
| `TSA_MATH_QUANT` | Function | Function expression | `SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE` | Tính và biến đổi biểu thức chứa hàm số hoặc đạo hàm. | Tìm giá trị biểu thức, nghiệm hoặc điều kiện của hàm. |
| `TSA_MATH_QUANT` | Function | Trigonometric function | `SKILL_MATH_TRIG_EQUATION_SOLVE` | Giải phương trình lượng giác trong miền xác định. | Tính tổng hoặc đếm nghiệm lượng giác. |
| `TSA_MATH_QUANT` | Geometry | Plane and solid geometry | `SKILL_MATH_GEOMETRY_MEASURE` | Tính độ dài, diện tích, thể tích và các đại lượng hình học. | Bài toán hình phẳng hoặc khối đa diện. |
| `TSA_MATH_QUANT` | Geometry | Coordinate geometry | `SKILL_MATH_COORDINATE_SPATIAL_ANALYZE` | Phân tích quan hệ điểm, đường, mặt trong hệ tọa độ. | Khẳng định đúng/sai về hình học không gian tọa độ. |
| `TSA_MATH_QUANT` | Combinatorics | Counting under constraints | `SKILL_MATH_COUNT_CONSTRAINED` | Đếm số cấu hình thỏa các điều kiện ràng buộc. | Số cách chia, xếp hoặc chọn. |
| `TSA_MATH_QUANT` | Probability | Repeated trials | `SKILL_MATH_PROBABILITY_CALCULATE` | Tính xác suất một hoặc nhiều biến cố trong phép thử lặp. | Xác suất có ít nhất k kết quả thỏa điều kiện. |
| `TSA_MATH_QUANT` | Statistics | Descriptive statistics | `SKILL_MATH_STATISTICS_INTERPRET` | So sánh và diễn giải chỉ số thống kê mô tả. | Trung bình, tỉ lệ, phân bố hoặc biến động dữ liệu. |
| `TSA_MATH_MODEL` | Mathematical modeling | Translate context to model | `SKILL_MATH_MODEL_FORMULATE` | Chuyển tình huống thực tế thành biến, quan hệ và ràng buộc toán học. | Bài toán kích thước, chi phí hoặc sản lượng. |
| `TSA_MATH_MODEL` | Mathematical modeling | Interpret model result | `SKILL_MATH_MODEL_INTERPRET` | Diễn giải nghiệm toán học theo ngữ cảnh và đơn vị ban đầu. | Chọn kết quả hợp lý trong bài toán thực tế. |
| `TSA_MATH_MODEL` | Optimization | Objective and constraints | `SKILL_MATH_OPTIMIZE` | Thiết lập mục tiêu, ràng buộc và tìm phương án tối ưu. | Tối thiểu chi phí hoặc tối đa diện tích/thể tích. |
| `TSA_MATH_REASON` | Algebraic reasoning | Parameter conditions | `SKILL_MATH_PARAMETER_REASON` | Suy luận điều kiện tham số từ số nghiệm hoặc giá trị cực trị. | Xác định miền của tham số. |
| `TSA_MATH_REASON` | Geometric reasoning | Relationship verification | `SKILL_MATH_GEOMETRY_VERIFY` | Kiểm tra tính đúng đắn của các quan hệ hình học bằng lập luận. | Câu đúng/sai về tính chất hình học. |
| `TSA_MATH_PROBLEM` | Problem solving | Constraint decomposition | `SKILL_MATH_CONSTRAINT_DECOMPOSE` | Chia bài toán thành các điều kiện nhỏ và chọn chiến lược giải phù hợp. | Phân chia hình hoặc bài toán nhiều điều kiện. |
| `TSA_MATH_PROBLEM` | Problem solving | Multi-step integration | `SKILL_MATH_MULTI_STEP_SOLVE` | Kết hợp nhiều kiến thức và kiểm tra tính hợp lệ của kết quả. | Bài toán có nhiều bước biến đổi. |

## Tư duy Đọc hiểu

| Competency | Domain | Topic | Proposed skill | Operational description | Example question form |
| --- | --- | --- | --- | --- | --- |
| `TSA_READ_COMPREHEND` | Text Type | Literary text | `SKILL_READ_LITERARY_IDENTIFY` | Nhận diện đặc điểm cơ bản của văn bản tự sự/văn học và mục đích đọc hiểu. | Câu hỏi về nhân vật hoặc sự kiện trong truyện. |
| `TSA_READ_COMPREHEND` | Text Type | Informational text | `SKILL_READ_INFORMATIONAL_IDENTIFY` | Nhận diện kiểu văn bản thông tin và thông tin trung tâm. | Câu hỏi theo bài báo hoặc văn bản khoa học phổ thông. |
| `TSA_READ_COMPREHEND` | Text Type | Argumentative text | `SKILL_READ_ARGUMENTATIVE_IDENTIFY` | Nhận diện văn bản nghị luận, chủ đề và mục đích giao tiếp. | Xác định mục đích chính của bài viết. |
| `TSA_READ_COMPREHEND` | Main Idea & Detail | Main idea | `SKILL_READ_MAIN_IDEA_IDENTIFY` | Xác định ý chính hoặc nội dung khái quát của văn bản/đoạn văn. | “Ý chính của bài viết là gì?” |
| `TSA_READ_COMPREHEND` | Main Idea & Detail | Explicit detail | `SKILL_READ_DETAIL_RETRIEVE` | Tìm và đối chiếu thông tin được nêu trực tiếp trong văn bản. | Đúng/sai, chọn phát biểu đúng, điền cụm từ. |
| `TSA_READ_COMPREHEND` | Main Idea & Detail | Statement completion | `SKILL_READ_STATEMENT_COMPLETE` | Hoàn thành nhận định bằng từ/cụm từ phù hợp từ văn bản. | Kéo thả hoặc điền khuyết. |
| `TSA_READ_ANALYZE` | Structure & Organization | Paragraph function | `SKILL_READ_STRUCTURE_ANALYZE` | Phân tích vai trò đoạn văn và cách tổ chức thông tin. | Xác định chức năng của đoạn hoặc trình tự lập luận. |
| `TSA_READ_ANALYZE` | Argument & Evidence | Claim and evidence | `SKILL_READ_EVIDENCE_ANALYZE` | Phân biệt luận điểm, bằng chứng và quan hệ hỗ trợ. | Nhận diện bằng chứng cho kết luận. |
| `TSA_READ_ANALYZE` | Argument & Evidence | Argument logic | `SKILL_READ_ARGUMENT_ANALYZE` | Phân tích cấu trúc, giả định hoặc tính nhất quán của lập luận. | Câu hỏi về lập luận trong văn bản. |
| `TSA_READ_INFER` | Inference | Implicit conclusion | `SKILL_READ_INFERENCE_DRAW` | Rút ra kết luận không được phát biểu trực tiếp. | Chọn kết luận hợp lý từ nhiều chi tiết. |
| `TSA_READ_INFER` | Inference | Cause and motive | `SKILL_READ_CAUSE_MOTIVE_INFER` | Suy ra nguyên nhân, động cơ hoặc hệ quả từ ngữ cảnh. | Vì sao nhân vật/hành động xảy ra. |
| `TSA_READ_EVALUATE` | Evaluation & Interpretation | Message | `SKILL_READ_MESSAGE_EVALUATE` | Đánh giá thông điệp, ý nghĩa hoặc giá trị của văn bản. | Phát biểu nào không phù hợp với thông điệp. |
| `TSA_READ_EVALUATE` | Evaluation & Interpretation | Argument quality | `SKILL_READ_ARGUMENT_EVALUATE` | Đánh giá mức độ phù hợp của quan điểm và bằng chứng. | Chọn đánh giá hợp lý về lập luận. |
| `TSA_READ_EVALUATE` | Evaluation & Interpretation | Title and interpretation | `SKILL_READ_TITLE_INTERPRET` | Chọn nhan đề hoặc diễn giải khái quát phù hợp với văn bản. | Đặt nhan đề khác cho văn bản. |

## Tư duy Khoa học/GQVĐ

| Competency | Domain | Topic | Proposed skill | Operational description | Example question form |
| --- | --- | --- | --- | --- | --- |
| `TSA_SCI_DATA` | Data Interpretation | Tables | `SKILL_SCI_TABLE_INTERPRET` | Đọc, so sánh và rút thông tin từ bảng số liệu. | Xác định giá trị hoặc so sánh các hàng/cột. |
| `TSA_SCI_DATA` | Data Interpretation | Charts | `SKILL_SCI_CHART_INTERPRET` | Đọc xu hướng, cực trị và giá trị từ biểu đồ. | Xác định loại có tỉ lệ cao nhất hoặc khoảng giá trị. |
| `TSA_SCI_DATA` | Data Interpretation | Statistical data | `SKILL_SCI_STATISTICAL_COMPARE` | So sánh tỉ lệ, biến động hoặc khác biệt giữa các nhóm dữ liệu. | Đúng/sai về chênh lệch theo giới tính hoặc thời gian. |
| `TSA_SCI_CONCEPT` | Biology / Health | Health terminology | `SKILL_SCI_HEALTH_TERM_APPLY` | Nhận diện và áp dụng thuật ngữ sinh học/y tế. | Điền khái niệm chuyên môn từ mô tả. |
| `TSA_SCI_CONCEPT` | Biology / Health | Cancer & Public Health | `SKILL_SCI_CANCER_KNOWLEDGE_APPLY` | Áp dụng kiến thức về ung thư, sức khỏe cộng đồng và yếu tố nguy cơ. | Câu hỏi về nguyên nhân hoặc đặc điểm dịch tễ. |
| `TSA_SCI_CONCEPT` | Chemistry | Substance properties | `SKILL_SCI_CHEM_PROPERTY_EXPLAIN` | Giải thích tính chất chất và hiện tượng hóa học. | Giải thích NaOH tăng khối lượng khi để ngoài không khí. |
| `TSA_SCI_CONCEPT` | Chemistry | Quantitative reaction | `SKILL_SCI_CHEM_QUANTITATIVE_CALCULATE` | Tính toán định lượng từ phản ứng hoặc thí nghiệm hóa học. | Thể tích dung dịch phản ứng hoặc khối lượng chất. |
| `TSA_SCI_CONCEPT` | Physics | Physical relationships | `SKILL_SCI_PHYSICS_RELATION_APPLY` | Áp dụng công thức và quan hệ vật lý vào hiện tượng. | Tính hoặc giải thích đại lượng vật lý. |
| `TSA_SCI_CONCEPT` | Earth & Environmental Science | Environmental systems | `SKILL_SCI_ENVIRONMENT_APPLY` | Áp dụng kiến thức về hệ Trái Đất và môi trường. | Giải thích hiện tượng môi trường. |
| `TSA_SCI_REASON` | Scientific Reasoning | Evidence | `SKILL_SCI_EVIDENCE_CONCLUDE` | Dùng bằng chứng hoặc dữ liệu để chọn kết luận phù hợp. | Kết luận nào được dữ liệu ủng hộ. |
| `TSA_SCI_REASON` | Scientific Reasoning | Cause & Effect | `SKILL_SCI_CAUSE_EFFECT_ANALYZE` | Phân tích nguyên nhân và hệ quả của hiện tượng khoa học. | Các yếu tố dẫn tới một hiện tượng. |
| `TSA_SCI_REASON` | Scientific Reasoning | Hypothesis | `SKILL_SCI_HYPOTHESIS_EVALUATE` | Dự đoán, kiểm tra hoặc đánh giá giả thuyết. | Dự đoán kết quả thí nghiệm. |
| `TSA_SCI_PROBLEM` | Problem Solving | Identify problem | `SKILL_SCI_PROBLEM_IDENTIFY` | Xác định câu hỏi, biến số và dữ kiện cần giải quyết. | Xác định vấn đề trọng tâm trong tình huống. |
| `TSA_SCI_PROBLEM` | Problem Solving | Analyze information | `SKILL_SCI_INFORMATION_ANALYZE` | Chọn lọc và tổ chức thông tin liên quan để xử lý vấn đề. | So sánh các dữ kiện trước khi đưa ra quyết định. |
| `TSA_SCI_PROBLEM` | Problem Solving | Select solution | `SKILL_SCI_SOLUTION_SELECT` | Lựa chọn phương án giải quyết có căn cứ. | Chọn giải pháp phù hợp nhất theo ràng buộc. |

## Coverage review: 39 extracted questions

`Candidate path` below is a review proposal, not approved metadata. `M1`–`M3`
is retained only when it was present in the extracted stem. A blank value means
that a reviewer must assign it from the question content.

### Mathematics: `HUST_TSA_LOCAL_000001`

| Question | Candidate path | Cognitive level | Review note |
| --- | --- | --- | --- |
| Q1 | `TSA_MATH_QUANT -> Number & Arithmetic -> Number properties -> SKILL_MATH_NUMBER_REMAINDER` | M1 | Also uses digit properties; select one primary skill. |
| Q2 | `TSA_MATH_QUANT -> Function -> Graph and variation -> SKILL_MATH_FUNCTION_GRAPH_ANALYZE` | M1 | Visual stimulus must be retained during review. |
| Q3 | `TSA_MATH_REASON -> Geometric reasoning -> Relationship verification -> SKILL_MATH_GEOMETRY_VERIFY` | M2 | Multi-statement true/false item. |
| Q4 | `TSA_MATH_QUANT -> Function -> Function expression -> SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE` | M2 | Includes equation/inequality subparts; review whether it must be split. |
| Q5 | `TSA_MATH_QUANT -> Probability -> Repeated trials -> SKILL_MATH_PROBABILITY_CALCULATE` | M2 | Probability of repeated digit selection. |
| Q6 | `TSA_MATH_PROBLEM -> Problem solving -> Constraint decomposition -> SKILL_MATH_CONSTRAINT_DECOMPOSE` | M2 | Number/divisibility is a secondary content tag. |
| Q7 | `TSA_MATH_QUANT -> Function -> Trigonometric function -> SKILL_MATH_TRIG_EQUATION_SOLVE` | M2 | Confirm parsed equation and interval from source PDF. |
| Q8 | `TSA_MATH_REASON -> Algebraic reasoning -> Parameter conditions -> SKILL_MATH_PARAMETER_REASON` | M2 | Relates a minimum value to a parameter range. |
| Q9 | `TSA_MATH_REASON -> Geometric reasoning -> Relationship verification -> SKILL_MATH_GEOMETRY_VERIFY` | M3 | Coordinate-space assertions. |
| Q10 | `TSA_MATH_MODEL -> Mathematical modeling -> Translate context to model -> SKILL_MATH_MODEL_FORMULATE` | M3 | Geometry measures are secondary. |
| Q11 | `TSA_MATH_MODEL -> Optimization -> Objective and constraints -> SKILL_MATH_OPTIMIZE` | M3 | Minimize material cost. |
| Q12 | `TSA_MATH_REASON -> Algebraic reasoning -> Parameter conditions -> SKILL_MATH_PARAMETER_REASON` | M3 | Count roots under a parameter condition. |

### Reading: `HUST_TSA_LOCAL_000002`

| Question | Candidate path | Cognitive level | Review note |
| --- | --- | --- | --- |
| Q1 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Main idea -> SKILL_READ_MAIN_IDEA_IDENTIFY` | — | Main idea of the article. |
| Q2 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Direct statement verification. |
| Q3 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Statement completion -> SKILL_READ_STATEMENT_COMPLETE` | — | Drag-and-drop completion. |
| Q4 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Direct statement verification. |
| Q5 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Statement completion -> SKILL_READ_STATEMENT_COMPLETE` | — | Drag-and-drop completion. |
| Q6 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Exact phrase retrieval. |
| Q7 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Main idea -> SKILL_READ_MAIN_IDEA_IDENTIFY` | — | Confirm whether the intended target is summary or detail. |
| Q8 | `TSA_READ_ANALYZE -> Structure & Organization -> Paragraph function -> SKILL_READ_STRUCTURE_ANALYZE` | — | Main purpose of the article. |
| Q9 | `TSA_READ_ANALYZE -> Structure & Organization -> Paragraph function -> SKILL_READ_STRUCTURE_ANALYZE` | — | Function/purpose of a paragraph. |
| Q10 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Direct statement verification. |
| Q11 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Exact phrase retrieval. |
| Q12 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Select the statement inconsistent with the text. |
| Q13 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Direct statement verification. |
| Q14 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Statement completion -> SKILL_READ_STATEMENT_COMPLETE` | — | Exact phrase completion. |
| Q15 | `TSA_READ_COMPREHEND -> Main Idea & Detail -> Explicit detail -> SKILL_READ_DETAIL_RETRIEVE` | — | Direct statement verification. |
| Q16 | `TSA_READ_INFER -> Inference -> Cause and motive -> SKILL_READ_CAUSE_MOTIVE_INFER` | — | Infer the character's primary motivation. |
| Q17 | `TSA_READ_INFER -> Inference -> Implicit conclusion -> SKILL_READ_INFERENCE_DRAW` | — | Infer implied attitude and conclusion. |
| Q18 | `TSA_READ_EVALUATE -> Evaluation & Interpretation -> Message -> SKILL_READ_MESSAGE_EVALUATE` | — | Evaluate consistency with the text's message. |
| Q19 | `TSA_READ_EVALUATE -> Evaluation & Interpretation -> Title and interpretation -> SKILL_READ_TITLE_INTERPRET` | — | Select a title from interpreted content. |

### Science/problem solving: `HUST_TSA_LOCAL_000003`

| Question | Candidate path | Cognitive level | Review note |
| --- | --- | --- | --- |
| Q1 | `TSA_SCI_CONCEPT -> Chemistry -> Substance properties -> SKILL_SCI_CHEM_PROPERTY_EXPLAIN` | — | Explain hygroscopic behavior of NaOH. |
| Q2 | `TSA_SCI_CONCEPT -> Chemistry -> Quantitative reaction -> SKILL_SCI_CHEM_QUANTITATIVE_CALCULATE` | — | Titration calculation. |
| Q3 | `TSA_SCI_CONCEPT -> Chemistry -> Quantitative reaction -> SKILL_SCI_CHEM_QUANTITATIVE_CALCULATE` | — | Calculate absorbed-water mass. |
| Q4 | `TSA_SCI_DATA -> Data Interpretation -> Charts -> SKILL_SCI_CHART_INTERPRET` | — | Read pH-volume chart under a threshold. |
| Q5 | `TSA_SCI_DATA -> Data Interpretation -> Charts -> SKILL_SCI_CHART_INTERPRET` | — | Identify highest value in a cancer chart. |
| Q6 | `TSA_SCI_REASON -> Scientific Reasoning -> Cause & Effect -> SKILL_SCI_CAUSE_EFFECT_ANALYZE` | — | Identify supported causes of an increase. |
| Q7 | `TSA_SCI_CONCEPT -> Biology / Health -> Health terminology -> SKILL_SCI_HEALTH_TERM_APPLY` | — | Identify the term for metastasis. |
| Q8 | `TSA_SCI_DATA -> Data Interpretation -> Statistical data -> SKILL_SCI_STATISTICAL_COMPARE` | — | Compare cancer rates by gender. |

## Coverage findings and decisions required before JSON implementation

| Area | Finding | Consequence |
| --- | --- | --- |
| Primary mapping | All 39 questions can receive a candidate primary path. | Mapping may proceed only as `needs_human_review`. |
| Mathematics | The 12 items cover all four Mathematics Competencies, but `TSA_MATH_PROBLEM` has only one candidate. | Do not infer a stable competency profile from this sample. |
| Reading | 13 of 19 items are direct comprehension/retrieval; inference, analysis, and evaluation have only 2 items each. | Add diverse reading items before applying score thresholds. |
| Science | `TSA_SCI_PROBLEM` has no candidate item; Physics and Earth/Environmental Science are also uncovered. | Keep these taxonomy branches as draft and do not use them for analytics yet. |
| Cognitive levels | Only Mathematics stems currently carry extracted M1/M2/M3 markers. | Assign levels for Reading and Science during human review; do not derive them automatically. |
| Source quality | Several items depend on a graph, diagram, table, or grouped subparts. | Preserve page/image context and verify extraction before publishing. |
| Dataset scale | 39 extracted items are far below a full TSA blueprint of 100 items. | The current dataset is suitable for pilot validation, not a full TSA test generator. |

## Approval checklist

Before converting this draft into `competency_framework.json` and a mapper,
reviewers must approve:

1. The primary-path rule and the proposed skills.
2. Any new Domain/Topic nodes required by this draft.
3. Each of the 39 candidate mappings.
4. Cognitive-level criteria for Reading and Science.
5. The treatment of multi-part, visual, and passage-based questions.
