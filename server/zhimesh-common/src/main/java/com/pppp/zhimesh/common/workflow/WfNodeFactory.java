package com.pppp.zhimesh.common.workflow;

import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.node.EndNode;
import com.pppp.zhimesh.common.workflow.node.answer.LLMAnswerNode;
import com.pppp.zhimesh.common.workflow.node.classifier.ClassifierNode;
import com.pppp.zhimesh.common.workflow.node.documentextractor.DocumentExtractorNode;
import com.pppp.zhimesh.common.workflow.node.faqextractor.FaqExtractorNode;
import com.pppp.zhimesh.common.workflow.node.google.GoogleNode;
import com.pppp.zhimesh.common.workflow.node.httprequest.HttpRequestNode;
import com.pppp.zhimesh.common.workflow.node.humanfeedback.HumanFeedbackNode;
import com.pppp.zhimesh.common.workflow.node.keywordextractor.KeywordExtractorNode;
import com.pppp.zhimesh.common.workflow.node.knowledgeretrieval.KnowledgeRetrievalNode;
import com.pppp.zhimesh.common.workflow.node.mailsender.MailSendNode;
import com.pppp.zhimesh.common.workflow.node.start.StartNode;
import com.pppp.zhimesh.common.workflow.node.switcher.SwitcherNode;
import com.pppp.zhimesh.common.workflow.node.template.TemplateNode;
import com.pppp.zhimesh.common.workflow.node.texttransform.TextTransformNode;
import com.pppp.zhimesh.common.workflow.node.variableaggregator.VariableAggregatorNode;

public class WfNodeFactory {
    public static AbstractWfNode create(WorkflowComponent wfComponent, WorkflowNode nodeDefinition, WfState wfState, WfNodeState nodeState) {
        AbstractWfNode wfNode = null;
        switch (WfComponentNameEnum.getByName(wfComponent.getName())) {
            case START:
                wfNode = new StartNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case LLM_ANSWER:
                wfNode = new LLMAnswerNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case CLASSIFIER:
                wfNode = new ClassifierNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case SWITCHER:
                wfNode = new SwitcherNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case TEMPLATE:
                wfNode = new TemplateNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case TEXT_TRANSFORM:
                wfNode = new TextTransformNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case VARIABLE_AGGREGATOR:
                wfNode = new VariableAggregatorNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case KEYWORD_EXTRACTOR:
                wfNode = new KeywordExtractorNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case DOCUMENT_EXTRACTOR:
                wfNode = new DocumentExtractorNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case FAQ_EXTRACTOR:
                wfNode = new FaqExtractorNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case KNOWLEDGE_RETRIEVER:
                wfNode = new KnowledgeRetrievalNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case GOOGLE_SEARCH:
                wfNode = new GoogleNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case HUMAN_FEEDBACK:
                wfNode = new HumanFeedbackNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case MAIL_SEND:
                wfNode = new MailSendNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case HTTP_REQUEST:
                wfNode = new HttpRequestNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            case END:
                wfNode = new EndNode(wfComponent, nodeDefinition, wfState, nodeState);
                break;
            default:
        }
        return wfNode;
    }
}
