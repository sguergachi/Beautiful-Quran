/**
 * First-bookmark lesson around the live ruby ribbon: holding it writes a note (Android BookmarkNoteTip).
 */
import { ContextualFeatureTip } from '../theme/ContextualFeatureTip'
import type { AyahSelectorSide } from '../../data/settings'

type Props = {
  visible: boolean
  ribbonSide: AyahSelectorSide
  targetCenterY: number
  surfaceWidth: number
  actionBottom: number
  onDismiss: () => void
  onRenderedChange?: (rendered: boolean) => void
}

export function BookmarkTip({
  visible,
  ribbonSide,
  targetCenterY,
  surfaceWidth,
  actionBottom,
  onDismiss,
  onRenderedChange,
}: Props) {
  const ribbonOnLeft = ribbonSide === 'left'
  return (
    <ContextualFeatureTip
      visible={visible}
      title="Add a note"
      body="Press and hold this ribbon."
      onDismiss={onDismiss}
      onRenderedChange={onRenderedChange}
      spotlightSide={ribbonSide}
      spotlightCenter={{
        x: ribbonOnLeft ? 14 : Math.max(14, surfaceWidth - 14),
        y: targetCenterY,
      }}
      placement={{
        bodyAngleDegrees: ribbonOnLeft ? 0 : 180,
      }}
      actionCenter={{
        x: ribbonOnLeft
          ? Math.max(52, surfaceWidth - 68)
          : 68,
        y: Math.max(52, actionBottom - 48),
      }}
      contentPadding={{
        start: ribbonOnLeft ? 18 : 32,
        end: ribbonOnLeft ? 32 : 18,
      }}
    />
  )
}
