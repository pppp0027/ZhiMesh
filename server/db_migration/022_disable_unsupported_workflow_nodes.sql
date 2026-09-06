-- Agent cannot bind its own knowledge bases or MCP tools, and image generation
-- is outside the supported workflow scope. The pre-delete audit confirms these
-- component rows have no workflow-node references, so they can be removed.
DELETE FROM adi_workflow_component
WHERE name IN ('Agent', 'OpenAiImage', 'Tongyiwanx');
