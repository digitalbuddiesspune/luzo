import { Suspense } from "react";
import { OnlineLevelsPageShell } from "@/app/components/ludo-shell";
import { cloneMockBootState } from "@/app/lib/mock-state";

export default function LevelsPage() {
  return (
    <Suspense fallback={null}>
      <OnlineLevelsPageShell appState={cloneMockBootState()} />
    </Suspense>
  );
}
