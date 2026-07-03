import { MenuPageShell } from "@/app/components/ludo-shell";
import { cloneMockBootState } from "@/app/lib/mock-state";

export default function Home() {
  return <MenuPageShell appState={cloneMockBootState()} />;
}
