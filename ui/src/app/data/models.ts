/** Shapes of the gateway REST bodies and /ws messages. Money and prices are decimal strings, shown as they arrive. */

export interface Instrument {
  venue: string;
  symbol: string;
  venueProductId: number;
  contractType: string;
  contractValue: string;
  tickSize: string;
  minSize: string;
  makerFee: string;
  takerFee: string;
  tradingEnabled: boolean;
  updatedAt: string;
}

export interface Quote {
  symbol: string;
  bestBid: string | null;
  bestAsk: string | null;
  markPrice: string | null;
  venueTime: string | null;
  receivedAt: string;
  stale: boolean;
}

export interface Candle {
  symbol: string;
  openTime: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
}

export interface Order {
  id: string;
  clientOrderId: string;
  idempotencyKey: string;
  venue: string;
  instrumentId: number;
  symbol: string;
  side: string;
  type: string;
  size: number;
  limitPrice: string | null;
  timeInForce: string;
  reduceOnly: boolean;
  state: string;
  filledSize: number;
  avgFillPrice: string | null;
  fees: string | null;
  venueOrderId: string | null;
  source: string;
  reason: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface OrderTransition {
  from: string | null;
  to: string;
  reason: string | null;
  at: string;
}

export interface Fill {
  id: string;
  orderId: string;
  venue: string;
  venueFillId: string;
  price: string;
  size: number;
  fee: string;
  liquidity: string | null;
  at: string;
}

export interface OrderDetails {
  order: Order;
  riskDecisionId: string | null;
  signalId: string | null;
  transitions: OrderTransition[];
  fills: Fill[];
}

export interface Position {
  venue: string;
  symbol: string;
  size: number;
  avgEntryPrice: string | null;
  realizedPnl: string | null;
  feesPaid: string | null;
  markPrice: string | null;
  unrealizedPnl: string | null;
}

export interface PnlSummary {
  realizedPnl: string | null;
  feesPaid: string | null;
  unrealizedPnl: string | null;
  netPnl: string | null;
}

export interface RiskStatus {
  halted: boolean;
  events: unknown[];
}

/** `order` message: camelCase Protobuf OrderUpdated. */
export interface OrderUpdate {
  orderId: string;
  clientOrderId: string;
  venue: string;
  symbol: string;
  side: string;
  orderType: string;
  size: number;
  limitPrice: string;
  timeInForce: string;
  reduceOnly: boolean;
  state: string;
  filledSize: number;
  avgFillPrice: string;
  fees: string;
  reason: string;
  source: string;
  updatedAt: string | null;
}

export interface FillCreated {
  fillId: string;
  orderId: string;
  venue: string;
  symbol: string;
  side: string;
  price: string;
  size: number;
  fee: string;
  liquidity: string;
  filledAt: string | null;
}

/** `position` message: camelCase Protobuf PositionUpdated. */
export interface PositionUpdate {
  venue: string;
  symbol: string;
  size: number;
  avgEntryPrice: string;
  realizedPnl: string;
  feesPaid: string;
  updatedAt: string | null;
}

export type LiveMessage =
  | { type: 'hello'; data: { tradingMode: string } }
  | { type: 'quote'; data: Quote[] }
  | { type: 'order'; data: OrderUpdate }
  | { type: 'fill'; data: FillCreated }
  | { type: 'position'; data: PositionUpdate }
  | { type: 'risk'; data: RiskStatus }
  | { type: 'upstream'; data: { upstream: 'core' | 'risk'; available: boolean } };

/** Why a panel's call failed: which upstream was down, or a generic error. */
export type PanelError = 'core' | 'risk' | 'error';
