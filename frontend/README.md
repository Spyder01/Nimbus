# Nimbus web application

The user interface: landing and sign-in, the dashboard, the canvas where apps are designed and deployed, settings, and the
admin pages. Built with React 19, TypeScript, Vite, Tailwind CSS 4, shadcn-style components on Base UI, React Router,
TanStack Query and React Flow.

In production this is built into static files and embedded in the backend, which serves it, so there is one thing to run.
See the [architecture](../docs/architecture.md#the-web-application) for how it fits in.

## Running it

You need Node.js 22. From this folder:

| Command | What it does |
|---|---|
| `npm ci` | Installs the exact dependencies. |
| `npm start` | Starts the dev server at http://localhost:5173 with hot reload. |
| `npm run lint` | Checks the code style. |
| `npm run build` | Type-checks and builds the production files into `dist/`. |

The dev server forwards `/api`, sign-in and logout requests to the backend on port 8080, so the browser sees one address
and cookies work as they will in production. Start the backend first ([Getting started](../docs/getting-started.md#option-b-run-from-source)).
Because of that, the GitHub OAuth App used in this mode needs the callback URL on port **5173**.

## How it is organised

| Folder | What is in it |
|---|---|
| `src/pages/` | One file per screen: landing, profile, dashboard, the canvas page, settings, and `admin/` (workers, one worker, members). |
| `src/features/apps/` | Listing, creating, importing and deleting apps; deployment state and the Open links. |
| `src/features/canvas/` | The editor: nodes and edges, the inspector panel, autosave, version history, deployment progress. |
| `src/features/images/` | The image catalog: search, the picker beside the image field, and the browse dialog. |
| `src/features/workers/` | The workers and pools admin: lists, settings forms, status badges. |
| `src/features/members/` | The members admin: list, search, and changing roles. |
| `src/components/` | Shared pieces: the app shell and navigation, theme, sign-in buttons, and `ui/` (the base components). |
| `src/lib/` | Small helpers: the API client, auth and roles, time formatting, downloads. |

## Conventions worth knowing

- **Server data goes through TanStack Query**, one hook per resource in each feature's `queries` file. Polling is switched on
  only while something is in progress.
- **The canvas owns the design once it has loaded.** It never refetches it underneath the person editing; saving is
  automatic and checked against a revision number.
- **Theme and colours come from tokens** in `src/index.css` (light and dark), not from fixed colours in components.
- **Roles gate the admin screens in the UI, but the server is what enforces them.** Hiding a button is a convenience, not security.
