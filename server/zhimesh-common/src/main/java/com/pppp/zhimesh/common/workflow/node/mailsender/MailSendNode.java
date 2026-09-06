package com.pppp.zhimesh.common.workflow.node.mailsender;

import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.ZhiMeshMailSender;
import com.pppp.zhimesh.common.util.AesUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.CustomMailInfo;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.WorkflowUtil;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.metrics.MailMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.MAIL_SENDER_TYPE_CUSTOM;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;

@Slf4j
public class MailSendNode extends AbstractWfNode {

    public MailSendNode(WorkflowComponent wfComponent, WorkflowNode node, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, node, wfState, nodeState);
        state.setMetrics(new MailMetrics());
    }

    @Override
    protected NodeProcessResult onProcess() {
        MailSendNodeConfig nodeConfig = checkAndGetConfig(MailSendNodeConfig.class);
        int senderType = nodeConfig.getSenderType();
        String subject = WorkflowUtil.renderTemplate(nodeConfig.getSubject(), state.getInputs());
        String content = WorkflowUtil.renderTemplate(nodeConfig.getContent(), state.getInputs());
        String toMails = WorkflowUtil.renderTemplate(nodeConfig.getToMails(), state.getInputs());
        if (StringUtils.isBlank(toMails)) {
            log.warn("Mail send node configuration error 1, {}", state.getUuid());
            throw new BaseException(A_MAIL_RECEIVER_EMPTY);
        }
        toMails = String.join(",", filterValidMails(toMails));
        if (StringUtils.isBlank(toMails)) {
            log.warn("Mail send node configuration error 2, {}", state.getUuid());
            throw new BaseException(A_MAIL_RECEIVER_EMPTY);
        }
        String ccMails = StringUtils.defaultString(nodeConfig.getCcMails(), "");
        if (StringUtils.isNotBlank(ccMails)) {
            String cmails = WorkflowUtil.renderTemplate(nodeConfig.getCcMails(), state.getInputs());
            ccMails = String.join(",", filterValidMails(cmails));
        }
        if (senderType == MAIL_SENDER_TYPE_CUSTOM) {
            MailSendNodeConfig.SenderInfo senderInfo = nodeConfig.getSender();
            if (senderInfo == null) {
                log.warn("Mail send node configuration error 3, {}", state.getUuid());
                throw new BaseException(A_MAIL_SENDER_EMPTY);
            }
            if (StringUtils.isAnyBlank(senderInfo.getName(), senderInfo.getMail(), senderInfo.getPassword())) {
                log.warn("Mail send node configuration error 4, {}", state.getUuid());
                throw new BaseException(A_MAIL_SENDER_CONFIG_ERROR);
            }
            ZhiMeshMailSender adiMailSender = SpringUtil.getBean(ZhiMeshMailSender.class);
            CustomMailInfo customMailInfo = new CustomMailInfo();
            setSmtpInfo(customMailInfo, nodeConfig.getSmtp());
            setSenderInfo(customMailInfo, senderInfo);
            setCustomMailInfo(customMailInfo, subject, content, toMails, ccMails);
            adiMailSender.customSend(customMailInfo);
        } else {
            ZhiMeshMailSender adiMailSender = SpringUtil.getBean(ZhiMeshMailSender.class);
            adiMailSender.send(subject, content, toMails, ccMails);
        }
        //记录邮件发送指标 | Record mail send metrics
        MailMetrics mailMetrics = (MailMetrics) state.getMetrics();
        mailMetrics.setRecipientCount(toMails.split(",").length);
        // SMTP accepted the message. Final delivery still depends on the recipient's mail provider.
        mailMetrics.setSendSuccess(true);
        NodeIOData output = NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", "Email accepted by SMTP server");
        return NodeProcessResult.builder().content(List.of(output)).build();
    }

    private void setCustomMailInfo(CustomMailInfo customMailInfo, String subject, String content, String toMails, String ccMails) {
        customMailInfo.setToMails(toMails);
        customMailInfo.setCcMails(ccMails);
        customMailInfo.setSubject(subject);
        customMailInfo.setContent(content);
    }

    private void setSmtpInfo(CustomMailInfo customMailInfo, MailSendNodeConfig.SmtpInfo smtpInfo) {
        customMailInfo.setHost(smtpInfo.getHost());
        customMailInfo.setPort(smtpInfo.getPort());
    }

    private void setSenderInfo(CustomMailInfo customMailInfo, MailSendNodeConfig.SenderInfo senderInfo) {
        customMailInfo.setSenderName(senderInfo.getName());
        customMailInfo.setSenderMail(senderInfo.getMail());
        String password = senderInfo.getPassword();
        String decrypt = AesUtil.decrypt(password);
        customMailInfo.setSenderPassword(decrypt);
    }

    private List<String> filterValidMails(String mails) {
        List<String> validMails = new ArrayList<>();
        String[] mailArray = mails.split(",");
        for (String mail : mailArray) {
            if (checkMail(mail)) {
                validMails.add(mail);
            } else {
                log.warn("Invalid email address, ignored, {}", mail);
            }
        }
        return validMails;
    }

    private boolean checkMail(String mail) {
        return Pattern.compile("^(.+)@(\\S+)$").matcher(mail).matches();
    }
}
