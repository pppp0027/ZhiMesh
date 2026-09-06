package com.pppp.zhimesh.common.vo;

import com.pppp.zhimesh.common.languagemodel.data.ModelVoice;
import lombok.Data;

import java.util.List;

@Data
public class SysConfigResp {
    private AsrSetting asrSetting;
    private TtsSetting ttsSetting;
    /**
     * ttsSetting中设置的 modelName 对应的可用语音列表
     */
    private List<ModelVoice> availableVoices;
    /**
     * Global default locale from system config
     */
    private String defaultLocale;

    /** Whether the user frontend may use Conversation APIs and conversation-scoped chat. */
    private boolean conversationEnabled;
}
