import { Suspense } from "react";
import { BoardPageShell } from "@/app/components/ludo-shell";
import { createPlayRouteState } from "@/app/lib/mock-state";

const mode = "online";

export default function OnlinePlayPage() {
  return (
    <Suspense fallback={null}>
      <BoardPageShell mode={mode} appState={createPlayRouteState(mode)} />
    </Suspense>
  );
}
