-- Whether a plan includes MCP access (personal tokens for AI agents) is a property of the plan, like its limits, and
-- not a check of a plan's name in the code. A plan without the key does not include it (unlike a limit, where no key
-- means no limit). Only Ultra has it now; giving it to another plan is one row.
UPDATE plans SET constraints = constraints || '{"mcp_access": true}'::jsonb WHERE name = 'unlimited';
