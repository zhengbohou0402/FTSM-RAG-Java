package com.ftsm.rag;

import com.ftsm.rag.service.ModelFactory;
import com.ftsm.rag.service.RagService;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
public class RagEvaluatorTest {

    @Autowired
    private RagService ragService;

    @Autowired
    private ModelFactory modelFactory;

    record TestCase(String question, String groundTruth) {}
    record TestResult(TestCase tc, double precision, double recall, double faithfulness, double relevance) {}

    @Test
    public void evaluatePipeline() {
        List<TestCase> testCases = List.of(
            new TestCase("Where is FTSM located?", "FTSM is located at UKM Bangi Campus."),
            new TestCase("What are the available Master's programs in FTSM?", "Master of Data Science, Master of Cyber Security, Master of Computer Science, and Master of Information Technology."),
            new TestCase("How long does the Master of Data Science program take?", "1 to 3 years depending on full-time or part-time mode."),
            new TestCase("Is there any industrial training for undergraduate students?", "Yes, undergraduate students are required to undergo industrial training."),
            new TestCase("Who is the Dean of FTSM?", "The current Dean of FTSM."),
            new TestCase("How to renew a visa for international students?", "Visa renewal is managed by EMGS, and international students should process it through the international office."),
            new TestCase("What is the difference between Master by Coursework and Master by Research?", "Coursework involves taking subjects and a project, while Research involves a full thesis."),
            new TestCase("Where can I find the faculty map?", "The faculty map is available on the student portal or the official FTSM website.")
        );

        List<TestResult> results = new ArrayList<>();

        for (TestCase tc : testCases) {
            log.info("Evaluating Question: {}", tc.question);
            
            // 1. Execute RAG pipeline
            String generatedAnswer = ragService.ragSummarize(tc.question).block();
            var preparedAnswer = ragService.prepareAnswer(tc.question).block();
            
            // Extract context (everything after Mandatory Retrieval Instructions in our prompt)
            String prompt = preparedAnswer.prompt();
            String context = prompt != null && prompt.contains("## Mandatory Retrieval Instructions") 
                    ? prompt.substring(prompt.indexOf("## Mandatory Retrieval Instructions")) 
                    : "";

            // 2. Evaluate Metrics
            double contextPrecision = evaluateContextPrecision(tc.question, context);
            double contextRecall = evaluateContextRecall(tc.question, tc.groundTruth, context);
            double faithfulness = evaluateFaithfulness(tc.question, generatedAnswer, context);
            double answerRelevance = evaluateAnswerRelevance(tc.question, generatedAnswer);
            
            results.add(new TestResult(tc, contextPrecision, contextRecall, faithfulness, answerRelevance));
            
            log.info("Metrics for '{}': Precision: {}, Recall: {}, Faithfulness: {}, Relevance: {}", 
                    tc.question, contextPrecision, contextRecall, faithfulness, answerRelevance);
                    
            // 3. Assertions
            assertThat(contextPrecision).isGreaterThan(0.7);
            assertThat(contextRecall).isGreaterThan(0.7);
            assertThat(faithfulness).isGreaterThan(0.7);
            assertThat(answerRelevance).isGreaterThan(0.7);
        }

        // 4. Output Markdown Report
        printMarkdownReport(results);
    }

    private double evaluateContextPrecision(String question, String context) {
        String prompt = String.format("Given the following context and question, determine the proportion of sentences in the context that are strictly relevant to answering the question. Output ONLY a score between 0.0 and 1.0.\n\nQuestion: %s\nContext: %s", question, context);
        return parseScore(modelFactory.getChatModel().generate(UserMessage.from(prompt)).content().text());
    }

    private double evaluateContextRecall(String question, String groundTruth, String context) {
        String prompt = String.format("Given the following context and ground truth answer, determine if the context contains sufficient information to answer the question completely as described in the ground truth. Output ONLY a score between 0.0 and 1.0.\n\nQuestion: %s\nGround Truth: %s\nContext: %s", question, groundTruth, context);
        return parseScore(modelFactory.getChatModel().generate(UserMessage.from(prompt)).content().text());
    }

    private double evaluateFaithfulness(String question, String generatedAnswer, String context) {
        String prompt = String.format("Given the following context and generated answer, determine if the generated answer is completely faithful to the context (i.e., no hallucinations or outside information). Output ONLY a score between 0.0 and 1.0.\n\nContext: %s\nGenerated Answer: %s", context, generatedAnswer);
        return parseScore(modelFactory.getChatModel().generate(UserMessage.from(prompt)).content().text());
    }

    private double evaluateAnswerRelevance(String question, String generatedAnswer) {
        String prompt = String.format("Given the following question and generated answer, determine how relevant the answer is to the question. Does it directly answer the user's intent without unrelated tangents? Output ONLY a score between 0.0 and 1.0.\n\nQuestion: %s\nGenerated Answer: %s", question, generatedAnswer);
        return parseScore(modelFactory.getChatModel().generate(UserMessage.from(prompt)).content().text());
    }

    private double parseScore(String text) {
        try {
            Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(text);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
            return 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
    
    private void printMarkdownReport(List<TestResult> results) {
        System.out.println("\n# RAG Evaluation Report\n");
        System.out.println("| Question | Context Precision | Context Recall | Faithfulness | Answer Relevance |");
        System.out.println("|----------|-------------------|----------------|--------------|------------------|");
        
        double totalPrecision = 0, totalRecall = 0, totalFaithfulness = 0, totalRelevance = 0;
        for (TestResult r : results) {
            System.out.printf("| %s | %.2f | %.2f | %.2f | %.2f |\n",
                    r.tc.question, r.precision, r.recall, r.faithfulness, r.relevance);
            totalPrecision += r.precision;
            totalRecall += r.recall;
            totalFaithfulness += r.faithfulness;
            totalRelevance += r.relevance;
        }
        
        int n = results.size();
        System.out.printf("| **AVERAGE** | **%.2f** | **%.2f** | **%.2f** | **%.2f** |\n\n",
                totalPrecision / n, totalRecall / n, totalFaithfulness / n, totalRelevance / n);
    }
}
