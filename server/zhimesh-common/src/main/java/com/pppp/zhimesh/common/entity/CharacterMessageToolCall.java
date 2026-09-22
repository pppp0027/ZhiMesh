package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 角色消息的工具调用轨迹实体：生成一条助手消息过程中实际执行的每次工具调用各占一行。
 * create_time 由数据库维护（DDL 默认值），与姊妹表 adi_character_message_ref_* 保持一致，
 * 不建外键。
 * <p>
 * Tool-call trace entity for character messages: one row per tool invocation
 * executed while producing an assistant message. create_time is owned by the
 * database (DDL default), consistent with the sibling tables
 * adi_character_message_ref_*; no foreign key is declared.
 */
@Data
@TableName("adi_character_message_tool_call")
@Schema(title = "角色消息-工具调用轨迹实体 | Character Message Tool Call Entity", description = "角色消息-工具调用轨迹列表 | Character Message Tool Call List")
public class CharacterMessageToolCall implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(title = "消息ID | Message ID")
    @TableField("message_id")
    private Long messageId;

    @Schema(title = "工具名称或标识 | Tool name or identifier")
    @TableField("tool_name")
    private String toolName;

    @Schema(title = "工具入参，JSON 字符串，可为空 | Tool arguments as a JSON string, nullable")
    @TableField("args")
    private String args;

    @Schema(title = "工具结果摘要，可为空 | Tool result summary, nullable")
    @TableField("result_summary")
    private String resultSummary;

    @Schema(title = "工具调用耗时，毫秒 | Tool call duration in milliseconds")
    @TableField("duration_ms")
    private Long durationMs;

    @Schema(title = "工具调用是否成功 | Whether the tool call succeeded")
    @TableField("success")
    private Boolean success;

    @Schema(title = "同一轮回答内的调用序号，从 0 开始 | Sequence number within one answer, starting at 0")
    @TableField("seq")
    private Integer seq;
}
