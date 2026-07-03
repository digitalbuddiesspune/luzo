import { redirect } from "next/navigation";
import { BoardPageShell } from "@/app/components/ludo-shell";
import { IS_FRIENDS_MODE_VISIBLE } from "@/app/lib/features";
import { createPlayRouteState } from "@/app/lib/mock-state";

const mode = "private-room";

export default function PrivateRoomPlayPage() {
  if (!IS_FRIENDS_MODE_VISIBLE) {
    redirect("/");
  }

  return <BoardPageShell mode={mode} appState={createPlayRouteState(mode)} />;
}
