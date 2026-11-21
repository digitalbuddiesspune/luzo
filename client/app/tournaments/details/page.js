"use client";
import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { TournamentDetails } from "../tournament-ui";
function Screen() { const id = useSearchParams().get("id"); return id ? <TournamentDetails key={id} id={id} /> : <p>Select a tournament from the tournament home page.</p>; }
export default function Page() { return <Suspense fallback={<p>Loading…</p>}><Screen /></Suspense>; }
