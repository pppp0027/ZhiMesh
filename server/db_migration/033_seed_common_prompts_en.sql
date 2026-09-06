-- Add common English prompts for existing users without overwriting templates with the same title.
INSERT INTO adi_prompt (user_id, act, prompt)
SELECT users.id, seeds.act, seeds.prompt
FROM adi_user AS users
CROSS JOIN (VALUES
    ('Summarize long content', $prompt$Read the content below and return: (1) a one-sentence conclusion, (2) three to five key points, (3) important facts or figures, and (4) actionable next steps. Stay faithful to the source, do not invent information, and label missing details as “Not stated in the source.”

Content:
{content}$prompt$),
    ('Polish writing', $prompt$Polish the text below without changing its meaning or facts. Correct grammar and spelling, remove repetition, strengthen transitions, and use clear, concise, professional language. Return only the revised text.

Original text:
{text}$prompt$),
    ('Translate Chinese and English', $prompt$Detect the language automatically. Translate Chinese into natural, accurate English and English into fluent, idiomatic Chinese. Preserve paragraphs, lists, proper nouns, numbers, and Markdown formatting. Return only the translation.

Source text:
{text}$prompt$),
    ('Create meeting minutes', $prompt$Turn the notes below into structured meeting minutes with: topic, key discussion points, confirmed decisions, action items (owner, deadline, deliverable), and open questions. Do not infer missing information; label it “To be confirmed.”

Meeting notes:
{notes}$prompt$),
    ('Write a professional email', $prompt$Write a concise, professional, and courteous email using the information below. Include a clear subject, greeting, context, request, expected action, and closing. Mark missing information with square brackets.

Recipient: {recipient}
Purpose: {purpose}
Key information: {key_information}
Tone: {formal/friendly/firm}$prompt$),
    ('Review and fix code', $prompt$Review the code below for correctness, edge cases, security, performance, maintainability, and concurrency risks. List findings by severity with reasons, then provide directly usable corrected code and essential tests. Preserve behavior unrelated to the identified issues.

Stack and context: {stack_and_context}
Code:
{code}$prompt$),
    ('Explain a complex concept', $prompt$Explain “{concept}” from first principles. Start with a one-sentence intuitive definition, use an everyday analogy, then cover key components, a concrete example, common misconceptions, and practical applications. Write for a {beginner/intermediate/expert} audience and use a table or steps when helpful.$prompt$),
    ('Brainstorm solutions', $prompt$Propose eight distinct, actionable approaches to “{goal_or_problem}.” For each approach, describe the core idea, best-fit scenario, benefits, risks, estimated effort, and first action. Compare them using consistent criteria and recommend the two best options to validate first.$prompt$),
    ('Break down a task', $prompt$Turn “{goal}” into an executable plan. Include the goal and success criteria, scope boundaries, phases and milestones, task list, priorities, suggested owners, dependencies, risks, and mitigations. End with the first three actions that can start today, and state assumptions when time or resources are unknown.$prompt$)
) AS seeds(act, prompt)
WHERE NOT EXISTS (
    SELECT 1
    FROM adi_prompt AS existing
    WHERE existing.user_id = users.id
      AND existing.act = seeds.act
);
