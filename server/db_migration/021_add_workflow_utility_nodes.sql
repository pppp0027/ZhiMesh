-- Adds deterministic, server-executed utility nodes for the visual workflow editor.
-- Safe to run repeatedly on PostgreSQL.
INSERT INTO adi_workflow_component (uuid, name, title, remark, display_order, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), 'TextTransform', '文本处理',
       '对输入文本执行清理、大小写转换或查找替换', 15, true
WHERE NOT EXISTS (SELECT 1 FROM adi_workflow_component WHERE name = 'TextTransform');

INSERT INTO adi_workflow_component (uuid, name, title, remark, display_order, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), 'VariableAggregator', '变量聚合',
       '将多个上游变量组合为一个文本输出，适合构建提示词或汇总内容', 16, true
WHERE NOT EXISTS (SELECT 1 FROM adi_workflow_component WHERE name = 'VariableAggregator');
