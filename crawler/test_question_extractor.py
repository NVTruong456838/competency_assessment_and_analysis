import unittest

from crawler.common.question_extractor import QuestionExtractor


class QuestionExtractorTests(unittest.TestCase):
    def test_extracts_question_options_and_answer(self):
        document = {
            "document_id": "HUST_TSA_000001",
            "source": "HUST_TSA",
            "source_url": "https://example.com/exam.pdf",
            "content_type": "pdf",
            "pages": [{
                "page": 4,
                "text": (
                    "Câu 1: Một phép tính có kết quả là gì?\n"
                    "A. Một\n"
                    "B. Hai\n"
                    "C. Ba\n"
                    "D. Bốn\n"
                    "Đáp án: C"
                ),
            }],
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(questions[0]["question_number"], 1)
        self.assertEqual(questions[0]["stem"], "Một phép tính có kết quả là gì?")
        self.assertEqual(questions[0]["options"]["C"], "Ba")
        self.assertEqual(questions[0]["answer"], ["C"])
        self.assertEqual(questions[0]["page_number"], 4)
        self.assertEqual(questions[0]["source_pages"], [4])

    def test_keeps_fill_in_question_without_options(self):
        document = {
            "source_url": "https://example.com/page",
            "content": "Câu 1: Điền kết quả vào chỗ trống.",
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(questions[0]["question_type"], "numeric_input")
        self.assertEqual(questions[0]["options"], {})

    def test_splits_inline_options(self):
        document = {
            "source_url": "https://example.com/exam.pdf",
            "content": (
                "Câu 1: Chọn đáp án đúng.\n"
                "A. Mười. B. Hai mươi. C. Ba mươi. D. Bốn mươi."
            ),
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(questions[0]["options"]["A"], "Mười.")
        self.assertEqual(questions[0]["options"]["D"], "Bốn mươi.")
        self.assertEqual(questions[0]["question_type"], "single_choice")

    def test_continues_question_options_across_pdf_pages(self):
        document = {
            "document_id": "HUST_TSA_LOCAL_000001",
            "source_url": "file:///exam.pdf",
            "content_type": "pdf",
            "pages": [
                {"page": 1, "text": "Câu 1: Chọn đáp án đúng."},
                {"page": 2, "text": "A. Một B. Hai C. Ba D. Bốn"},
            ],
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(questions[0]["page_number"], 1)
        self.assertEqual(questions[0]["source_pages"], [1, 2])
        self.assertEqual(set(questions[0]["options"]), {"A", "B", "C", "D"})

    def test_marks_visual_references_for_review(self):
        question = QuestionExtractor().extract_from_document({
            "content": "Câu 1: Dựa vào biểu đồ, chọn đáp án đúng.\nA. Một\nB. Hai",
        })[0]

        self.assertIn("visual_content_needs_review", question["quality"]["warnings"])

    def test_does_not_treat_figurative_hinh_anh_as_a_figure_reference(self):
        question = QuestionExtractor().extract_from_document({
            "content": "Câu 1: Hình ảnh người mẹ trong bài thơ gợi cảm nghĩ về điều gì?",
        })[0]

        self.assertNotIn(
            "visual_content_needs_review",
            question["quality"]["warnings"],
        )

    def test_does_not_treat_english_figure_of_speech_as_a_visual(self):
        question = QuestionExtractor().extract_from_document({
            "content": "Question 1: Marie Curie was an important figure in science.",
        })[0]

        self.assertNotIn(
            "visual_content_needs_review",
            question["quality"]["warnings"],
        )

    def test_does_not_treat_math_graph_mention_as_an_embedded_graph(self):
        question = QuestionExtractor().extract_from_document({
            "content": "Câu 1: Tiệm cận xiên của đồ thị hàm số là gì?",
        })[0]

        self.assertNotIn(
            "visual_content_needs_review",
            question["quality"]["warnings"],
        )

    def test_recognizes_explicitly_numbered_figure_reference(self):
        question = QuestionExtractor().extract_from_document({
            "content": "Câu 1: Tính diện tích miền trong hình 2.",
        })[0]

        self.assertIn("visual_content_needs_review", question["quality"]["warnings"])

    def test_recovers_options_split_across_text_lines(self):
        document = {
            "source_url": "file:///exam.pdf",
            "content_type": "pdf",
            "pages": [{
                "page": 1,
                "text": (
                    "Câu 1: Tính kết quả.\n"
                    "A.\n11 B.\n9 C.\n6 D.\n5"
                ),
            }],
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(
            questions[0]["options"],
            {"A": "11", "B": "9", "C": "6", "D": "5"},
        )

    def test_classifies_true_false_inline_options(self):
        document = {
            "source_url": "https://example.com/exam.pdf",
            "content": (
                "Câu 1: Nhận định đúng hay sai? "
                "A. Đúng B. Sai Đáp án: Sai"
            ),
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(questions[0]["question_type"], "true_false")
        self.assertEqual(questions[0]["options"], {"A": "Đúng", "B": "Sai"})
        self.assertEqual(questions[0]["answer"], ["B"])

    def test_extracts_text_answer_for_fill_in_question(self):
        document = {
            "source_url": "https://example.com/exam.pdf",
            "content": "Câu 1: Điền đáp án vào chỗ trống. Đáp án: 42",
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(questions[0]["question_type"], "numeric_input")
        self.assertEqual(questions[0]["answer"], ["42"])

    def test_classifies_numeric_answer_without_numeric_stem_keyword(self):
        document = {
            "content": "Câu 1: Tính tổng hai giá trị đã cho. Đáp án: 42",
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["question_type"], "numeric_input")
        self.assertEqual(question["answer"], ["42"])

    def test_preserves_multiple_correct_answers_as_list(self):
        document = {
            "document_id": "DOC-1",
            "content": (
                "Câu 1: Chọn hai đáp án đúng.\n"
                "A. Một\nB. Hai\nC. Ba\nD. Bốn\n"
                "Đáp án: B & D"
            ),
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["question_type"], "multiple_choice")
        self.assertEqual(question["answer"], ["B", "D"])

    def test_recognizes_unaccented_multiple_select_instruction(self):
        document = {
            "content": (
                "Câu 1: Chon cac dap an dung.\n"
                "A. Mot\nB. Hai\nC. Ba\nD. Bon\n"
                "Đáp án: A, C"
            ),
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["question_type"], "multiple_choice")
        self.assertEqual(question["answer"], ["A", "C"])

    def test_recognizes_additional_multiple_select_phrasing(self):
        document = {
            "content": (
                "Câu 1: Có hai đáp án đúng.\n"
                "A. Một\nB. Hai\nC. Ba\nD. Bốn\n"
                "Đáp án: A và C"
            ),
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["question_type"], "multiple_choice")
        self.assertEqual(question["answer"], ["A", "C"])

    def test_cognitive_marker_does_not_determine_question_type(self):
        document = {
            "content": (
                "Câu 1: (M2) Tính tổng.\n"
                "A. Một\nB. Hai\nC. Ba\nD. Bốn"
            ),
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["question_type"], "single_choice")

    def test_discards_empty_question_header(self):
        document = {
            "content": "Câu 1:\n\nCâu 2: Câu hỏi có nội dung.",
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual(len(questions), 1)
        self.assertEqual(questions[0]["question_number"], 2)

    def test_incomplete_choice_is_kept_and_marked_for_review(self):
        document = {
            "content": "Câu 1: Chọn đáp án đúng.\nA. Một",
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["question_type"], "single_choice")
        self.assertEqual(question["options"], {"A": "Một"})
        self.assertEqual(question["quality"]["extraction_status"], "needs_review")
        self.assertIn("choice_options_incomplete", question["quality"]["warnings"])

    def test_out_of_order_options_are_flagged_not_silently_reordered(self):
        document = {
            "content": (
                "Câu 1: Chọn đáp án đúng.\n"
                "A. Một\nC. Ba\nB. Hai\nD. Bốn"
            ),
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(set(question["options"]), {"A", "B", "C", "D"})
        self.assertEqual(question["quality"]["extraction_status"], "needs_review")
        self.assertIn("unexpected_option_order", question["quality"]["warnings"])

    def test_nonmatching_choice_answer_is_null_and_needs_review(self):
        document = {
            "content": (
                "Câu 1: Chọn đáp án đúng.\n"
                "A. Một\nB. Hai\nC. Ba\nD. Bốn\n"
                "Đáp án: Không xác định"
            ),
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertIsNone(question["answer"])
        self.assertEqual(question["quality"]["extraction_status"], "needs_review")
        self.assertIn("missing_answer", question["quality"]["warnings"])

    def test_unknown_text_answer_becomes_null(self):
        document = {
            "content": "Câu 1: Điền cụm từ vào chỗ trống. Đáp án: Không xác định",
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertIsNone(question["answer"])
        self.assertIn("missing_answer", question["quality"]["warnings"])

    def test_two_single_choice_options_are_flagged_for_review(self):
        document = {
            "content": "Câu 1: Chọn đáp án đúng. A. Một B. Hai",
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["question_type"], "single_choice")
        self.assertIn(
            "possible_merged_options",
            question["quality"]["warnings"],
        )

    def test_groups_passage_questions_without_contaminating_prior_option(self):
        document = {
            "document_id": "DOC-2",
            "content": (
                "Câu 7: Câu hỏi độc lập.\n"
                "A. Một\nB. Hai\nC. Ba\nD. Bốn\n"
                "Đọc văn bản sau và trả lời câu hỏi từ 8 đến 9.\n"
                "Đây là nội dung ngữ liệu chung.\n"
                "Câu 8: Câu hỏi thuộc văn bản.\n"
                "A. Năm\nB. Sáu\nC. Bảy\nD. Tám\n"
                "Câu 9: Câu tiếp theo thuộc văn bản.\n"
                "A. Chín\nB. Mười\nC. Mười một\nD. Mười hai\n"
                "Câu 10: Câu hỏi độc lập sau ngữ liệu."
            ),
        }

        questions = QuestionExtractor().extract_from_document(document)
        prior, first_grouped, second_grouped, following = questions

        self.assertEqual(prior["options"]["D"], "Bốn")
        self.assertNotIn("group_id", prior)
        self.assertEqual(first_grouped["group_id"], second_grouped["group_id"])
        self.assertEqual(first_grouped["group_type"], "passage")
        self.assertIn("ngữ liệu chung", first_grouped["passage"])
        self.assertEqual(first_grouped["page_number"], None)
        self.assertNotIn("group_id", following)

    def test_closes_passage_range_without_tu(self):
        questions = QuestionExtractor().extract_from_document({
            "document_id": "DOC-RANGE",
            "content": (
                "Đọc đoạn trích dưới đây và trả lời các câu hỏi 66 đến 67.\n"
                "Ngữ liệu chung.\n"
                "Câu hỏi 66: Câu đầu trong nhóm.\n"
                "Câu hỏi 67: Câu cuối trong nhóm.\n"
                "Câu hỏi 68: Câu độc lập kế tiếp."
            ),
        })

        self.assertEqual(questions[0]["group_id"], questions[1]["group_id"])
        self.assertNotIn("group_id", questions[2])

    def test_removes_footnote_from_wrapped_option_and_marks_review(self):
        document = {
            "source_url": "file:///exam.pdf",
            "content": (
                "Câu 1: Chọn đáp án đúng.\n"
                "A. Một\nB. Hai\nC. Ba\n"
                "D. Bốn\n2 Tức là chú thích cuối trang."
            ),
        }

        question = QuestionExtractor().extract_from_document(document)[0]

        self.assertEqual(question["options"]["D"], "Bốn")
        self.assertEqual(question["quality"]["extraction_status"], "needs_review")
        self.assertIn(
            "footnote_removed_from_option",
            question["quality"]["warnings"],
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
