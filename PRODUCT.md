# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

- Team members who use models, knowledge bases, memory, tools, and workflows to complete AI-assisted work.
- Administrators who provision those capabilities, manage access and quotas, and monitor runtime health.

## Product Purpose

ZhiMesh is a team-oriented AI application platform. It gives people one place to chat with configured characters and models, ask questions over knowledge bases, run workflows, use MCP tools, and create images. It gives administrators the controls needed to operate the same capability network.

## Positioning

The product connects model, knowledge, memory, tool, and workflow capabilities into one usable loop. The user experience consumes that network, while the management experience provisions and governs it.

## Operating Context

The user-facing application is served at the web root and the administration application is served under `/admin/`; both use the shared backend API under `/api/`. The primary demonstration path is administration setup followed by user consumption and operational review.

## Capabilities and Constraints

- Existing Vue 3, TypeScript, Vite, Pinia, Vue Router, Naive UI, i18n, and API integrations are in scope for preservation.
- Existing routes, form field names, API contracts, permissions, streaming chat behavior, workflow editing, knowledge-base processing, MCP configuration, and image/audio features must continue to work.
- The two frontends are separate pnpm projects with version drift; a shared visual token contract is preferred before a shared component package.
- Content, metrics, customer claims, and operational data must come from the product or be clearly marked as sample data.

## Brand Commitments

- Product name: 知脉（ZhiMesh）.
- The user approved the Cognitive Mesh / 智识网络 direction for the visual redesign.
- The existing logo and purple glass treatment are not binding brand assets and may be replaced during the redesign.

## Evidence on Hand

- Root README and architecture documentation describe chat, RAG knowledge bases, long-term memory, workflows, image generation, voice, MCP, model management, and separate user/admin applications.
- Existing user and admin routes and implementations provide the functional baseline.
- No approved external logo, font, photography, customer proof, or marketing claims were supplied.

## Product Principles

- Make capability relationships understandable.
- Keep the user path focused on completing work.
- Keep administration focused on health, impact, and recoverable actions.
- Preserve advanced controls without exposing low-level detail by default.
- Treat state, evidence, permissions, and errors as first-class product information.

## Accessibility & Inclusion

- Preserve and improve keyboard access, visible focus, readable contrast, responsive layouts, internationalization, and `prefers-reduced-motion` behavior.
- Do not rely on color alone to communicate status.
