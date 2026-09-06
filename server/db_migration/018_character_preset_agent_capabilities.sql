BEGIN;

ALTER TABLE adi_character_preset
    ADD COLUMN IF NOT EXISTS mcp_ids varchar(1000) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS is_system boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN adi_character_preset.mcp_ids IS
    'Recommended MCP service IDs copied when the user enables this preset';
COMMENT ON COLUMN adi_character_preset.is_system IS
    'Built-in preset marker; all presets can be deleted by administrators';

UPDATE adi_character_preset
SET is_system = true,
    is_deleted = false,
    kb_title = CASE uuid
        WHEN '26a8f54c560948d6b2d4969f08f3f2fb' THEN '开发工程知识库'
        WHEN 'c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8' THEN '数据分析资料库'
        WHEN 'a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2' THEN '翻译术语知识库'
        WHEN 'e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6' THEN '论文写作资料库'
        WHEN 'a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8' THEN '产品需求知识库'
        WHEN 'd6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1' THEN '法律法规知识库'
        WHEN 'a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4' THEN '设计规范知识库'
        WHEN 'f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9' THEN '客服话术知识库'
        ELSE kb_title
    END
WHERE uuid IN (
    '26a8f54c560948d6b2d4969f08f3f2fb', 'c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8',
    'a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2', 'e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6',
    'a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8', 'd6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1',
    'a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4', 'f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9'
);

COMMIT;
