import { BoardPageShell } from "@/app/components/ludo-shell";
import { createPlayRouteState } from "@/app/lib/mock-state";

const mode = "computer";

export default function ComputerPlayPage() {
  return <BoardPageShell mode={mode} appState={createPlayRouteState(mode)} />;
}
