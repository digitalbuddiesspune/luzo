import { BoardPageShell } from "@/app/components/ludo-shell";
import { createPlayRouteState } from "@/app/lib/mock-state";

const mode = "local";

export default function LocalPlayPage() {
  return <BoardPageShell mode={mode} appState={createPlayRouteState(mode)} />;
}
