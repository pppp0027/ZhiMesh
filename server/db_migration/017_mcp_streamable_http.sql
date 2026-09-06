-- Add the MCP Streamable HTTP transport introduced by the current MCP specification.
ALTER TABLE adi_mcp
    ADD COLUMN IF NOT EXISTS streamable_http_url varchar(250) DEFAULT '' NOT NULL;

COMMENT ON COLUMN adi_mcp.transport_type
    IS 'Transport type: sse, streamable_http, stdio';
COMMENT ON COLUMN adi_mcp.streamable_http_url
    IS 'Streamable HTTP MCP endpoint URL';
