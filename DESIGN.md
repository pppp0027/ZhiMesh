# 知脉（ZhiMesh）Design Contract

Status: Direction A, Cognitive Mesh / 智识网络

This document is the working visual contract for the two ZhiMesh frontends. It is intentionally product-specific so a future page cannot drift back to a generic AI template.

## World

ZhiMesh is a capability network for teams. Models, knowledge, memory, tools, and workflows are represented as connected operational resources. The user frontend consumes that network to complete work. The administration frontend provisions, governs, and observes the same network.

The interface should feel like a quiet instrument panel made from mineral paper, graphite, ink-blue controls, and a small amount of teal signal. It should communicate evidence and state before decoration.

## Two densities

| Surface | Job | Density | Motion | Design variance |
| --- | --- | ---: | ---: | ---: |
| User workspace | Start and complete a task | 5/10 | 4/10 | 5/10 |
| Mesh Ops console | Configure, govern, and recover | 7/10 | 2/10 | 3/10 |

The user shell keeps the active work area calm and gives creation actions a clear place. The admin shell keeps tables, filters, metrics, and recoverable actions close together.

## Color tokens

Light surfaces use mineral white and pale blue-grey. Graphite carries primary text. Ink blue expresses authority and selected controls. Teal is reserved for a live connection, healthy state, or completed action. Amber is reserved for a caution or cost signal. Red is reserved for failure and destructive confirmation.

    ink control       #234a7a
    ink hover         #1b3c63
    signal teal       #16877d
    page background   #f1f5f7
    surface           #ffffff
    surface soft      #e9eff2
    text              #172532
    muted text        #5d6c78 / #60717c
    border            #d7e0e5
    subtle border     #e3eaee

Dark mode separates readable status color from solid control color:

    readable blue     #78a6c9
    readable hover    #91bad7
    control blue      #315b86
    control hover     #3d6b98
    signal teal       #4bb8ad
    page background   #111b24
    surface           #182631 / #1d2d39

Contrast is semantic, not just tonal. Solid ink-blue controls always use white foreground text and icons. Pale information surfaces use a dedicated dark ink-blue foreground in light mode and a pale blue foreground in dark mode. Never place the readable blue foreground directly on the solid control-blue surface, and never use a solid-button background rule that also catches text, ghost, secondary, tertiary, or quaternary button variants.

Never use purple, cyan-blue gradients, full-page color blobs, or a glowing accent as a substitute for hierarchy.

## Surfaces and geometry

- Core work surfaces (messages, forms, tables, cards) stay opaque with a border and a quiet shadow so content remains easy to scan.
- A restrained frosted layer is allowed only on navigation rails, sticky headers, drawers, popovers, and other transient controls. The web implementation is an approximation using a translucent fill, a 16–20px backdrop blur, a hairline highlight, and a tinted shadow. It is not applied page-wide.
- Every frosted surface has an opaque fallback for browsers without `backdrop-filter` and for `prefers-reduced-transparency: reduce`. Text contrast is checked against the fallback surface as well as the blurred surface.
- Use 8px for controls, 10px for cards, and 14px for larger workspace panels. Reserve pill shapes for counts and compact status labels.
- Use a border before a shadow. Shadows should indicate separation, not make every element float.
- Selected navigation is a solid ink-blue block with white icon and text. Hover is a pale surface change with no lift.
- Tooltips are ink-blue with white text and a matching arrow. They must be excluded from the white popover surface rule so collapsed navigation labels remain visible.
- Cards, dialogs, tables, and forms use the same border and radius language in both frontends.

### Glass tokens

The two shells expose the same semantic material tokens: `glass-nav`, `glass-nav-strong`, `glass-soft-alpha`, `glass-edge`, `glass-highlight`, `glass-blur`, and `glass-shadow`. A route may consume these tokens, but it should not invent a second glass recipe.

## Typography and copy

Use the existing system Chinese stack: PingFang SC, Microsoft YaHei, system-ui, and the platform sans fallback. Keep headings compact and descriptive. Avoid generic marketing lines such as "the future of AI". Prefer operational copy such as "从配置到使用" and "查看每一次处理的状态和影响".

Every meaningful empty state should answer three questions: what is missing, why it matters, and what the next action is. Errors must explain recovery. Metrics must retain their real API fields and must not be replaced by invented demo numbers.

Visible guidance follows a one-job rule: one short explanation beside a control is enough. Do not repeat the same instruction in a page subtitle, tab hint, empty state, and tooltip. Decorative slogans, playful filler, and index labels are removed when the surrounding controls already explain the action.

## Icon and status rules

- Icons are line or compact filled symbols from the existing icon libraries.
- The ZhiMesh mark is a dark-blue geometric mesh with a small number of teal nodes. It has no gradient, glow, or drop shadow.
- Color is never the only status signal. Pair teal, amber, or red with a label, icon, or text.
- A loading state should show a quiet node/connection motion and must stop or simplify under prefers-reduced-motion.

## Motion

Motion communicates a state change or spatial relationship only. Use short opacity or color transitions for controls. Avoid animating width, height, margin, padding, or layout position. Do not make every button rise on hover. Respect the global reduced-motion rule in both shells.

## Responsive behavior

### User frontend

- Desktop uses an icon rail, a conversation rail, and the main work plane.
- At 640px and below, the primary navigation becomes a fixed bottom bar. It spans the viewport, overrides the component library max width, includes safe-area padding, and leaves equivalent bottom space in the workspace.
- Conversation and composer controls may stack at 720px and below. Touch targets remain at least 40px where practical.

### Admin frontend

- Desktop uses a full-height governance rail and a border-led content plane.
- The header is a flat 60px row with breadcrumb and explicit action buttons. It is not a floating glass card.
- At 800px and below, content margins tighten. At 720px and below, breadcrumb detail is hidden and language controls collapse to a short label.
- List toolbars wrap on narrow screens. Refresh, density, and column controls have explicit labels and touch-sized buttons.

## State contract

Every major route must have a designed version of these states:

1. Loading: preserve the surrounding layout and use a skeleton or quiet progress mark.
2. Empty: describe the missing resource and provide the next valid action.
3. Error: keep the page usable, show a recoverable message, and provide retry where the API permits it.
4. Permission or unauthenticated: explain why the action is unavailable and provide login or request-access action.
5. Success: confirm the completed action without relying on a transient color flash.

Dashboard metrics render zero as a real value. Failed statistics requests keep the structure visible and expose a recovery message. API-backed pages must continue to use their existing contracts and permissions.

## Route ownership

- User shell: chat, knowledge base, workflow, MCP, image/audio creation, settings, and login.
- Admin shell: dashboard, model/platform configuration, knowledge resources, characters, MCP governance, quotas, and system settings.

When a new page is added, it must declare which side owns it, which density it uses, and which state contract it implements before adding bespoke decoration.

## Verification

- user-web: pnpm exec vue-tsc --noEmit --incremental false and pnpm run build-only pass.
- admin-web: pnpm run build passes. The repository does not include the vue-tsc executable for this package.
- Browser checks were run at 1440x900 and 390x844 for the user chat shell and admin login shell. The user mobile navigation spans the full bottom viewport without horizontal overflow.
- Impeccable detector was run once on the changed UI targets. The bundled HTML parser dependencies were unavailable, so it used degraded regex matching. The only reported finding was a side-rail width transition, which was removed.

Form seed: 73b16682.
