// api/get-quiz.js
export default async function handler(req, res) {
    // 開啟跨域放行，讓你的 Vercel 前端與手機網頁可以自由存取
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET');
    
    const { mode } = req.query;
    
    // 🔒 這把鑰匙直接讀取我們之前在 Vercel 後台藏好的 Environment Variables！
    const GEMINI_API_KEY = process.env.GEMINI_API_KEY;

    if (!GEMINI_API_KEY) {
        return res.status(500).json({ error: "雲端環境變數未設定，請確認 Vercel 後台" });
    }

    const descMap = {
        "earthquake": "情境：深夜突然發生規模 6.5 強震，屋內停電且物品散落。請針對【地震當下應變】或【摸黑尋找與使用避難包】設計一道引導式情境單選題。",
        "typhoon": "情境：強烈颱風登陸，你家已經停水停電進入第二天，外面風雨交加無法出門。請針對【長期停水電的物資分配】或【防颱居家準備不足時的補救】設計一道引導式情境單選題。",
        "fire": "情境：你住的大樓深夜發生火警，門外樓梯間已經充滿濃煙。請針對【火場逃生決策】或【如何運用身邊物資防煙】設計一道引導式情境單選題。",
        "home-safety": "情境：你正在進行居家安全大檢修，發現熱水器裝在通風不良的室內陽台，且家中浴室地板濕滑無防滑措施。請針對【一氧化碳中毒防範】或【居家防跌倒/防墜落安全預防】設計一道引導式情境單選題。",
        "first-aid": "情境：在路邊目擊一位路人突然倒地、失去意識且沒有呼吸。或者有人切菜時手指大出血。請針對【CPR與AED使用關鍵步驟】或【正確的止血與包紮急救法】設計一道引導式情境單選題。"
    };

    const prompt = `你是一位防災教育專家，負責進行「斷點引導式教學」。
    ${descMap[mode || 'earthquake']}
    請務必回傳嚴格的標準 JSON 格式，不要夾帶 Markdown：
    {
      "question": "情境敘述 + 題目",
      "options": ["選項A", "選項B", "選項C", "選項D"],
      "correctAnswer": "正確選項的完整文字",
      "explanation": "請先告知使用者他目前的觀念哪裡卡住了，然後給予正確的防護觀念指導。"
    }`;

    try {
        const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=${GEMINI_API_KEY}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] })
        });

        const data = await response.json();
        const rawText = data.candidates[0].content.parts[0].text;
        const cleanJsonText = rawText.replace(/```json/g, '').replace(/```/g, '').trim();
        
        return res.status(200).json(JSON.parse(cleanJsonText));
    } catch (error) {
        return res.status(500).json({ error: "AI生成失敗", details: error.message });
    }
}