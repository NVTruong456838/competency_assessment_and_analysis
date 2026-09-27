# HSA 2026 Skill Taxonomy Draft

## Status and scope

- Status: `draft`
- Taxonomy owner: Smart Assessment AI internal framework
- Canonical Competency, Domain, and Topic IDs:
  `crawler/taxonomy/competency_framework.json`
- Applicable exam structure: `crawler/data/processed/hsa/hsa_2026_framework.json`
- HSA Question Bank: not available yet; this document contains no question
  mapping and no published metadata.

The proposed Skill IDs use the following convention:

```text
SKILL_HSA_<SECTION>_<TOPIC>_<ACTION>
```

Each future question must receive one primary path:

```text
Section -> Competency -> Domain -> Topic -> Skill
```

`HSA_OPTIONAL` is only a selection container. A Part 3 question belongs to
either `HSA_SCIENCE` or `HSA_ENGLISH`; a Science question must also include its
`subject_choice`.

## HSA_MATH_DATA — Toán học và xử lý số liệu

| Competency | Domain / Topic | Proposed skill | Operational description |
| --- | --- | --- | --- |
| `HSA_MATH_LOGIC_QUANT` | Number, proposition and set | `SKILL_HSA_MATH_NUMBER_SET_REASON` | Phân tích mệnh đề, tập hợp, quan hệ số và điều kiện logic. |
| `HSA_MATH_LOGIC_QUANT` | Algebraic expression | `SKILL_HSA_MATH_ALGEBRA_MANIPULATE` | Biến đổi, rút gọn và so sánh biểu thức đại số. |
| `HSA_MATH_LOGIC_QUANT` | Equation and inequality | `SKILL_HSA_MATH_EQUATION_INEQUALITY_SOLVE` | Giải phương trình/bất phương trình và kiểm tra điều kiện nghiệm. |
| `HSA_MATH_LOGIC_QUANT` | Function and graph | `SKILL_HSA_MATH_FUNCTION_GRAPH_ANALYZE` | Đọc đồ thị, phân tích biến thiên và các đặc trưng của hàm số. |
| `HSA_MATH_LOGIC_QUANT` | Calculus | `SKILL_HSA_MATH_CALCULUS_APPLY` | Vận dụng giới hạn, đạo hàm, nguyên hàm hoặc tích phân. |
| `HSA_MATH_LOGIC_QUANT` | Plane geometry | `SKILL_HSA_MATH_PLANE_GEOMETRY_REASON` | Lập luận và tính toán quan hệ hình học phẳng. |
| `HSA_MATH_LOGIC_QUANT` | Spatial geometry | `SKILL_HSA_MATH_SPATIAL_GEOMETRY_REASON` | Lập luận quan hệ đường, mặt và khối trong không gian. |
| `HSA_MATH_LOGIC_QUANT` | Measurement | `SKILL_HSA_MATH_MEASUREMENT_CALCULATE` | Tính độ dài, diện tích, thể tích và đơn vị đo phù hợp. |
| `HSA_MATH_MODEL` | Real-context modelling | `SKILL_HSA_MATH_MODEL_FORMULATE` | Chuyển dữ kiện thực tế thành biến, quan hệ và mô hình toán học. |
| `HSA_MATH_MODEL` | Model interpretation | `SKILL_HSA_MATH_MODEL_INTERPRET` | Diễn giải nghiệm và kiểm tra tính hợp lý của kết quả theo ngữ cảnh. |
| `HSA_MATH_MODEL` | Objective and constraint | `SKILL_HSA_MATH_OBJECTIVE_CONSTRAINT_DEFINE` | Xác định mục tiêu, biến quyết định và ràng buộc của bài toán. |
| `HSA_MATH_MODEL` | Optimal solution | `SKILL_HSA_MATH_OPTIMIZE` | Chọn hoặc tính phương án tối ưu theo mô hình. |
| `HSA_MATH_PROBLEM` | Multi-step problem | `SKILL_HSA_MATH_MULTI_STEP_SOLVE` | Lập kế hoạch và thực hiện chuỗi bước giải có liên kết. |
| `HSA_MATH_PROBLEM` | Constraint and case | `SKILL_HSA_MATH_CONSTRAINT_CASE_ANALYZE` | Phân tích ràng buộc, chia trường hợp và loại trừ phương án không hợp lệ. |
| `HSA_MATH_DATA_ANALYSIS` | Data representation | `SKILL_HSA_MATH_DATA_REPRESENTATION_INTERPRET` | Đọc và chuyển đổi bảng, biểu đồ hoặc cách biểu diễn dữ liệu. |
| `HSA_MATH_DATA_ANALYSIS` | Descriptive statistics | `SKILL_HSA_MATH_DESCRIPTIVE_STATISTICS_CALCULATE` | Tính và diễn giải số đo thống kê mô tả. |
| `HSA_MATH_DATA_ANALYSIS` | Counting | `SKILL_HSA_MATH_COUNTING_APPLY` | Đếm cấu hình theo nguyên tắc tổ hợp phù hợp. |
| `HSA_MATH_DATA_ANALYSIS` | Event and probability | `SKILL_HSA_MATH_PROBABILITY_CALCULATE` | Xác định biến cố và tính xác suất. |

## HSA_LITERATURE_LANGUAGE — Văn học - Ngôn ngữ

| Competency | Domain / Topic | Proposed skill | Operational description |
| --- | --- | --- | --- |
| `HSA_LANG_LANGUAGE_USE` | Vocabulary and grammar | `SKILL_HSA_LANG_VOCABULARY_GRAMMAR_USE` | Chọn và sử dụng từ vựng, cấu trúc ngữ pháp đúng ngữ cảnh. |
| `HSA_LANG_LANGUAGE_USE` | Semantics and communication | `SKILL_HSA_LANG_SEMANTICS_PRAGMATICS_ANALYZE` | Phân tích nghĩa, hàm ý và hiệu quả giao tiếp. |
| `HSA_LANG_LANGUAGE_USE` | Sentence expression | `SKILL_HSA_LANG_SENTENCE_EXPRESSION_EDIT` | Chọn hoặc chỉnh sửa cách diễn đạt câu rõ ràng, đúng chuẩn. |
| `HSA_LANG_LANGUAGE_USE` | Coherence and cohesion | `SKILL_HSA_LANG_COHERENCE_COHESION_EVALUATE` | Nhận diện và cải thiện liên kết, mạch lạc của văn bản. |
| `HSA_LANG_READING` | Main idea and detail | `SKILL_HSA_LANG_MAIN_IDEA_DETAIL_IDENTIFY` | Xác định ý chính và thông tin được nêu trực tiếp. |
| `HSA_LANG_READING` | Purpose and viewpoint | `SKILL_HSA_LANG_PURPOSE_VIEWPOINT_IDENTIFY` | Xác định mục đích, quan điểm hoặc thái độ trong văn bản. |
| `HSA_LANG_READING` | Literary text | `SKILL_HSA_LANG_LITERARY_TEXT_COMPREHEND` | Hiểu nhân vật, sự kiện, chi tiết và ngữ cảnh của văn bản văn học. |
| `HSA_LANG_READING` | Informational text | `SKILL_HSA_LANG_INFORMATIONAL_TEXT_COMPREHEND` | Đọc hiểu thông tin, dữ kiện và quan hệ trong văn bản thông tin. |
| `HSA_LANG_TEXT_ANALYSIS` | Structure and organization | `SKILL_HSA_LANG_STRUCTURE_ANALYZE` | Phân tích cấu trúc, trình tự và vai trò của các phần văn bản. |
| `HSA_LANG_TEXT_ANALYSIS` | Argument and evidence | `SKILL_HSA_LANG_ARGUMENT_EVIDENCE_ANALYZE` | Phân tích luận điểm, bằng chứng và quan hệ hỗ trợ. |
| `HSA_LANG_TEXT_ANALYSIS` | Genre and image | `SKILL_HSA_LANG_GENRE_IMAGE_ANALYZE` | Phân tích thể loại, hình tượng và phương thức biểu đạt. |
| `HSA_LANG_TEXT_ANALYSIS` | Theme and artistry | `SKILL_HSA_LANG_THEME_ARTISTRY_ANALYZE` | Phân tích nội dung tư tưởng và nghệ thuật của tác phẩm. |
| `HSA_LANG_EVALUATION` | Argument quality | `SKILL_HSA_LANG_ARGUMENT_QUALITY_EVALUATE` | Đánh giá độ hợp lý, thuyết phục và giới hạn của lập luận. |
| `HSA_LANG_EVALUATION` | Language and style | `SKILL_HSA_LANG_LANGUAGE_STYLE_EVALUATE` | Đánh giá hiệu quả của lựa chọn ngôn ngữ và phong cách. |
| `HSA_LANG_EVALUATION` | Value and interpretation | `SKILL_HSA_LANG_VALUE_INTERPRET` | Diễn giải và đánh giá giá trị nội dung, tư tưởng của tác phẩm. |
| `HSA_LANG_EVALUATION` | Comparison | `SKILL_HSA_LANG_COMPARISON_EVALUATE` | So sánh văn bản, quan điểm hoặc cách thể hiện trên tiêu chí rõ ràng. |

## HSA_SCIENCE — Khoa học

Every Science mapping adds one `subject_choice`: `PHYSICS`, `CHEMISTRY`,
`BIOLOGY`, `HISTORY`, or `GEOGRAPHY`.

| Competency | Domain / Topic | Proposed skill | Operational description |
| --- | --- | --- | --- |
| `HSA_SCI_CONCEPT` | Physics: Mechanics | `SKILL_HSA_SCI_PHYSICS_MECHANICS_APPLY` | Vận dụng động học, động lực học, năng lượng hoặc động lượng. |
| `HSA_SCI_CONCEPT` | Physics: Waves, electricity and magnetism | `SKILL_HSA_SCI_PHYSICS_WAVE_ELECTRICITY_APPLY` | Vận dụng quan hệ dao động, sóng, điện và từ. |
| `HSA_SCI_CONCEPT` | Physics: Thermal and modern physics | `SKILL_HSA_SCI_PHYSICS_THERMAL_MODERN_APPLY` | Vận dụng kiến thức nhiệt, khí lí tưởng, hạt nhân hoặc phóng xạ. |
| `HSA_SCI_CONCEPT` | Chemistry: Atomic structure, bonding and energy | `SKILL_HSA_SCI_CHEM_ATOMIC_ENERGY_APPLY` | Giải thích cấu tạo, liên kết và biến đổi năng lượng hóa học. |
| `HSA_SCI_CONCEPT` | Chemistry: Inorganic and metals | `SKILL_HSA_SCI_CHEM_INORGANIC_METAL_APPLY` | Vận dụng kiến thức vô cơ, kim loại và phức chất. |
| `HSA_SCI_CONCEPT` | Chemistry: Organic compounds | `SKILL_HSA_SCI_CHEM_ORGANIC_APPLY` | Vận dụng tính chất và phản ứng của hợp chất hữu cơ. |
| `HSA_SCI_CONCEPT` | Chemistry: Spectroscopy and practice | `SKILL_HSA_SCI_CHEM_SPECTROSCOPY_PRACTICE_INTERPRET` | Diễn giải phổ hoặc dữ liệu thí nghiệm hóa học. |
| `HSA_SCI_CONCEPT` | Biology: Cell and organism | `SKILL_HSA_SCI_BIO_CELL_ORGANISM_APPLY` | Vận dụng kiến thức tế bào, vi sinh vật và cơ thể sống. |
| `HSA_SCI_CONCEPT` | Biology: Metabolism and development | `SKILL_HSA_SCI_BIO_METABOLISM_DEVELOPMENT_APPLY` | Giải thích trao đổi chất, sinh trưởng và phát triển. |
| `HSA_SCI_CONCEPT` | Biology: Genetics and evolution | `SKILL_HSA_SCI_BIO_GENETICS_EVOLUTION_APPLY` | Vận dụng quy luật di truyền và tiến hóa. |
| `HSA_SCI_CONCEPT` | Biology: Ecology and environment | `SKILL_HSA_SCI_BIO_ECOLOGY_ENVIRONMENT_APPLY` | Giải thích quan hệ sinh thái và vấn đề môi trường. |
| `HSA_SCI_CONCEPT` | History: World modern history | `SKILL_HSA_SCI_HISTORY_WORLD_MODERN_ANALYZE` | Xác định và phân tích sự kiện, tiến trình lịch sử thế giới. |
| `HSA_SCI_CONCEPT` | History: Southeast Asia | `SKILL_HSA_SCI_HISTORY_SEA_ANALYZE` | Phân tích sự kiện và quan hệ trong lịch sử Đông Nam Á. |
| `HSA_SCI_CONCEPT` | History: Vietnam modern history | `SKILL_HSA_SCI_HISTORY_VIETNAM_MODERN_ANALYZE` | Vận dụng kiến thức lịch sử Việt Nam cận và hiện đại. |
| `HSA_SCI_CONCEPT` | History: Integration | `SKILL_HSA_SCI_HISTORY_INTEGRATION_ANALYZE` | Phân tích danh nhân, hội nhập và bối cảnh lịch sử Việt Nam. |
| `HSA_SCI_CONCEPT` | Geography: General geography | `SKILL_HSA_SCI_GEO_GENERAL_APPLY` | Vận dụng quy luật và quá trình địa lí đại cương. |
| `HSA_SCI_CONCEPT` | Geography: World socioeconomics | `SKILL_HSA_SCI_GEO_WORLD_SOCIOECONOMIC_ANALYZE` | Phân tích đặc điểm kinh tế - xã hội của thế giới. |
| `HSA_SCI_CONCEPT` | Geography: Vietnam natural and population | `SKILL_HSA_SCI_GEO_VIETNAM_NATURAL_POPULATION_ANALYZE` | Phân tích điều kiện tự nhiên, dân cư và phân bố ở Việt Nam. |
| `HSA_SCI_CONCEPT` | Geography: Vietnam economic regions | `SKILL_HSA_SCI_GEO_VIETNAM_ECONOMIC_REGION_ANALYZE` | Phân tích ngành, vùng và chuyển dịch cơ cấu kinh tế Việt Nam. |
| `HSA_SCI_DATA` | Tables and charts | `SKILL_HSA_SCI_TABLE_CHART_INTERPRET` | Đọc, so sánh và rút thông tin từ bảng hoặc biểu đồ. |
| `HSA_SCI_DATA` | Experimental and practice data | `SKILL_HSA_SCI_EXPERIMENTAL_DATA_INTERPRET` | Diễn giải dữ liệu thí nghiệm/thực hành và nhận diện sai số cơ bản. |
| `HSA_SCI_DATA` | Maps and spatial data | `SKILL_HSA_SCI_MAP_SPATIAL_INTERPRET` | Đọc bản đồ, biểu đồ không gian và quan hệ phân bố. |
| `HSA_SCI_DATA` | Historical sources | `SKILL_HSA_SCI_HISTORICAL_SOURCE_INTERPRET` | Khai thác thông tin, ngữ cảnh và độ tin cậy từ tư liệu lịch sử. |
| `HSA_SCI_REASON` | Evidence and conclusion | `SKILL_HSA_SCI_EVIDENCE_CONCLUSION_REASON` | Rút ra hoặc đánh giá kết luận dựa trên bằng chứng. |
| `HSA_SCI_REASON` | Cause and effect | `SKILL_HSA_SCI_CAUSE_EFFECT_REASON` | Phân tích quan hệ nguyên nhân - hệ quả. |
| `HSA_SCI_REASON` | Prediction | `SKILL_HSA_SCI_PREDICTION_REASON` | Dự đoán kết quả từ mô hình, dữ kiện hoặc quy luật. |
| `HSA_SCI_REASON` | Hypothesis evaluation | `SKILL_HSA_SCI_HYPOTHESIS_EVALUATE` | Đánh giá giả thuyết dựa trên dữ liệu và điều kiện kiểm chứng. |
| `HSA_SCI_PROBLEM` | Identify problem and variables | `SKILL_HSA_SCI_VARIABLE_IDENTIFY` | Xác định vấn đề, biến số và dữ kiện liên quan. |
| `HSA_SCI_PROBLEM` | Select method | `SKILL_HSA_SCI_METHOD_SELECT` | Lựa chọn phương pháp hoặc quy trình xử lý phù hợp. |
| `HSA_SCI_PROBLEM` | Context analysis | `SKILL_HSA_SCI_CONTEXT_ANALYZE` | Phân tích bối cảnh xã hội/môi trường để xác định ràng buộc. |
| `HSA_SCI_PROBLEM` | Select solution | `SKILL_HSA_SCI_SOLUTION_SELECT` | So sánh và chọn giải pháp có căn cứ. |

## HSA_ENGLISH — Tiếng Anh

English cognitive metadata must use the separate proficiency scale configured
for HSA 2026 (Vietnamese levels 2–5 / A2–C1); it must not be automatically
equated with the internal M1–M3 levels.

| Competency | Domain / Topic | Proposed skill | Operational description |
| --- | --- | --- | --- |
| `HSA_ENG_LANGUAGE_USE` | Vocabulary in context | `SKILL_HSA_ENG_VOCABULARY_CONTEXT_USE` | Chọn nghĩa hoặc cách dùng từ phù hợp với ngữ cảnh. |
| `HSA_ENG_LANGUAGE_USE` | Grammar | `SKILL_HSA_ENG_GRAMMAR_USE` | Vận dụng cấu trúc ngữ pháp chính xác. |
| `HSA_ENG_LANGUAGE_USE` | Sentence transformation | `SKILL_HSA_ENG_SENTENCE_TRANSFORM` | Chuyển đổi câu mà vẫn bảo toàn nghĩa và tính đúng đắn. |
| `HSA_ENG_LANGUAGE_USE` | Coherence and cohesion | `SKILL_HSA_ENG_COHERENCE_COHESION_USE` | Chọn cấu trúc liên kết và trình tự diễn đạt mạch lạc. |
| `HSA_ENG_READING` | Main idea and detail | `SKILL_HSA_ENG_MAIN_IDEA_DETAIL_IDENTIFY` | Xác định ý chính và chi tiết trong đoạn đọc. |
| `HSA_ENG_READING` | Contextual meaning | `SKILL_HSA_ENG_CONTEXTUAL_MEANING_INFER` | Suy ra nghĩa từ/cụm từ theo ngữ cảnh. |
| `HSA_ENG_READING` | Text structure | `SKILL_HSA_ENG_TEXT_STRUCTURE_ANALYZE` | Phân tích cấu trúc và quan hệ giữa các phần văn bản. |
| `HSA_ENG_READING` | Author purpose and attitude | `SKILL_HSA_ENG_AUTHOR_PURPOSE_ATTITUDE_IDENTIFY` | Xác định mục đích, thái độ hoặc quan điểm của tác giả. |
| `HSA_ENG_SITUATIONAL_REASONING` | Communicative response | `SKILL_HSA_ENG_COMMUNICATIVE_RESPONSE_SELECT` | Chọn phản hồi giao tiếp phù hợp với tình huống. |
| `HSA_ENG_SITUATIONAL_REASONING` | Register and appropriacy | `SKILL_HSA_ENG_REGISTER_APPROPRIACY_EVALUATE` | Đánh giá mức độ phù hợp về sắc thái, vai giao tiếp và ngữ cảnh. |
| `HSA_ENG_SITUATIONAL_REASONING` | Implicit inference | `SKILL_HSA_ENG_IMPLICIT_INFERENCE_DRAW` | Suy ra thông tin, ý định hoặc kết luận không nêu trực tiếp. |
| `HSA_ENG_SITUATIONAL_REASONING` | Situational solution | `SKILL_HSA_ENG_SITUATIONAL_SOLUTION_SELECT` | Chọn cách giải quyết tình huống bằng tiếng Anh có căn cứ. |

## Review gate before HSA mapping

1. Review whether each proposed Skill is observable from a single question.
2. Review Domain/Topic coverage against the first permitted public HSA source.
3. Confirm the single primary-path rule for questions that include both subject
   knowledge and data interpretation.
4. Define review criteria for HSA difficulty levels 1–3 and for English
   proficiency levels 2–5 separately.
5. Create candidate mappings only after the HSA Question Bank has passed
   extraction and structural validation.
