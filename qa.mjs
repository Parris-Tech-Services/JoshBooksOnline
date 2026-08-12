import { chromium } from 'playwright';

(async () => {
  console.log("Starting QA test with Playwright for JoshBooks...");
  const browser = await chromium.launch();
  const page = await browser.newPage();
  
  const errors = [];
  page.on('console', msg => {
    // Next-Auth usually throws a 401 when first loading if not logged in, ignore that
    if (msg.type() === 'error' && !msg.text().includes('401') && !msg.text().includes('403')) {
      errors.push(`Console Error: ${msg.text()}`);
    }
  });
  
  page.on('pageerror', error => {
    errors.push(`Page Error: ${error.message}`);
  });

  try {
    await page.goto('http://localhost:3000/', { waitUntil: 'networkidle' });
    const title = await page.title();
    console.log(`Visited Homepage. Title: ${title}`);
    
    const bodyText = await page.evaluate(() => document.body.innerText.substring(0, 100));
    console.log(`Page starts with: ${bodyText.replace(/\n/g, ' ')}`);

    await page.waitForTimeout(1000);
    
    console.log("\n--- QA Results ---");
    if (errors.length > 0) {
      console.log("Encountered errors during QA:");
      errors.forEach(e => console.log(e));
    } else {
      console.log("No console or page errors encountered! Frontend is solid.");
    }
    
  } catch (err) {
    console.error("Test failed to execute:", err);
  } finally {
    await browser.close();
  }
})();
