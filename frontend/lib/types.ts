// Shapes returned by the Spring API (backend/src/main/java/dev/seatline/api).

export type Role = "FAN" | "ORGANIZER" | "DOOR_STAFF" | "ADMIN";
export type TierKind = "STANDARD" | "EARLY_BIRD" | "STUDENT" | "VIP";
export type SeatStatus = "FREE" | "HELD" | "SOLD";

export interface Me {
  id: string;
  email: string;
  displayName: string | null;
  role: Role;
  twoFactor: boolean;
}

export interface Pricing {
  feeBps: number;
  feeFlatCents: number;
  taxBps: number;
}

export interface Tier {
  id: string;
  name: string;
  kind: TierKind;
  priceCents: number;
  allInCents: number;
  endsAt: string | null;
  sections: string[];
}

export interface SeatlineEvent {
  id: string;
  venueId: string;
  organizerId: string;
  name: string;
  startsAt: string;
  status: "DRAFT" | "ON_SALE" | string;
  perFanLimit: number;
  pricing: Pricing;
  queuePerMinute: number | null;
  onSaleAt: string | null;
  tiers: Tier[];
  fromCents: number | null;
}

export interface Seat {
  label: string;
  row: string;
  number: number;
  accessible: boolean;
  limitedView: boolean;
  x: number;
  y: number;
}

export interface Section {
  id: string;
  name: string;
  seats: Seat[];
}

export interface Venue {
  id: string;
  name: string;
  city: string;
  sections: Section[];
}

export interface SeatState {
  seat: string;
  status: SeatStatus;
}

export interface PriceLine {
  face: number;
  discount: number;
  fee: number;
  tax: number;
  total: number;
}

export interface Quote {
  lines: { seat: string; tier: string; price: PriceLine }[];
  totalCents: number;
  promoCode: string | null;
}

export interface Hold {
  holdId: string;
  expiresAt: string;
  quote: Quote;
}

export interface Ticket {
  id: string;
  eventId: string;
  eventName: string;
  seat: string;
  tier: string;
  priceCents: number;
}

export interface Order {
  id: string;
  eventId: string;
  totalCents: number;
  tickets: Ticket[];
}

export interface QueueStatus {
  admitted: boolean;
  position: number;
  estimatedWaitSeconds: number;
  passExpiresAt: string | null;
  opensAt: string | null;
}

export interface Stats {
  seats: number;
  free: number;
  held: number;
  sold: number;
  orders: number;
  tickets: number;
  revenueCents: number;
  returned: number;
  scanned: number;
  waiting: number;
}

export interface LedgerNode {
  id: number;
  reachable: boolean;
  up: boolean;
  role: string;
  term: number;
  leader: number;
  commitIndex: number;
  lastApplied: number;
  lastIndex: number;
  cutOff: number[];
}
