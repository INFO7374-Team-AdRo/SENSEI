# Safety Dashboard

This is the Next.js frontend for the Industrial Safety Agent project. It renders live gas, thermal, and visual monitoring data from the Akka backend running on `http://localhost:8080`.

## Pages

- `/`: operations dashboard with live charts, camera panels, alert feed, and sensor bars
- `/incidents`: incident list and post-incident reports
- `/chat`: grounded chat assistant with saved conversations
- `/fault`: Akka shard kill-and-recovery demo

## Run Locally

```powershell
npm install
npm run dev
```

Open `http://localhost:3000`.

## Backend Dependency

The frontend expects the Java backend to already be running on `http://localhost:8080`.

The API base URL is currently hard-coded in:

- [app/lib/api.ts](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/safety-dashboard/app/lib/api.ts)

If you change the backend host or port, update that file as well.

## Main Frontend Files

- [app/page.tsx](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/safety-dashboard/app/page.tsx): live monitoring dashboard
- [app/incidents/page.tsx](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/safety-dashboard/app/incidents/page.tsx): incident explorer and reports
- [app/chat/page.tsx](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/safety-dashboard/app/chat/page.tsx): operator chat UI
- [app/fault/page.tsx](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/safety-dashboard/app/fault/page.tsx): fault tolerance demo

## Build

```powershell
npm run build
npm run start
```

For project-wide setup and architecture details, see the root [README.md](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/README.md).
